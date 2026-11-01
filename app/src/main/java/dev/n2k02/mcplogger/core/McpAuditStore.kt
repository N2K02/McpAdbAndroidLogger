package dev.n2k02.mcplogger.core

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * App-private, no-backup MCP audit journal. Only sanitized, size-limited
 * snapshots are written. Never stores headers, OAuth forms, or raw tokens.
 */
internal object McpAuditStore {
    val entries = MutableStateFlow<List<McpAuditEntry>>(emptyList())
    private val lock = Any()
    private const val FILE = "mcp-audits.json"
    private var lastDeniedAtMs = 0L

    private fun file(c: Context) = AtomicFile(File(c.noBackupFilesDir, FILE))
    private fun read(c: Context): List<McpAuditEntry> = runCatching {
        val f = file(c)
        if (!f.baseFile.isFile) return@runCatching emptyList()
        val data = f.openRead().bufferedReader().use { it.readText().take(5_000_000) }
        val array = JSONArray(data)
        (0 until array.length()).mapNotNull { index ->
            runCatching { McpAuditEntry.fromJson(array.getJSONObject(index)) }.getOrNull()
        }.takeLast(McpAuditPolicy.KEEP)
    }.getOrDefault(emptyList())


    /** Persist an atomic, bounded snapshot; empty means no previous-day journal. */
    private fun persist(c: Context, all: List<McpAuditEntry>) {
        val destination = file(c)
        if (all.isEmpty()) {
            destination.delete()
            return
        }
        val json = JSONArray()
        all.forEach { json.put(it.toJson()) }
        val stream = destination.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            destination.finishWrite(stream)
        } catch (error: Exception) {
            destination.failWrite(stream)
            throw error
        }
    }

    /** Prune previous-day entries on load, remote read, write and midnight tick. */
    private fun pruneLocked(c: Context, nowMs: Long = System.currentTimeMillis()): List<McpAuditEntry> {
        val old = read(c)
        val current = McpAuditPolicy.today(old, nowMs)
        if (old.size != current.size) persist(c, current)
        entries.value = current
        return current
    }

    fun prune(c: Context) = synchronized(lock) { pruneLocked(c); Unit }
    fun reload(c: Context) = prune(c)

    /** Read a consistent today-only snapshot after the local opt-in gate. */
    fun readForAgent(c: Context): List<McpAuditEntry> =
        synchronized(lock) { pruneLocked(c) }

    fun clear(c: Context) = synchronized(lock) {
        entries.value = emptyList()
        file(c).delete()
    }

    fun record(c: Context, identity: Agent, request: String, response: String,
               method: String, tool: String, status: String, durationMs: Long,
               httpMethod: String = "", endpoint: String = "",
               httpStatus: Int = 0, reason: String = "") =
        synchronized(lock) {
            runCatching {
                val input = McpAuditPolicy.snapshot(request, McpAuditPolicy.MAX_REQUEST_CHARS)
                val output = McpAuditPolicy.snapshot(response, McpAuditPolicy.MAX_RESPONSE_CHARS)
                val entry = McpAuditEntry(UUID.randomUUID().toString(),
                    System.currentTimeMillis(),
                    McpAuditPolicy.snapshot(identity.id, 120).text,
                    McpAuditPolicy.snapshot(identity.name, 100).text,
                    McpAuditPolicy.snapshot(identity.domain, 150).text,
                    McpAuditPolicy.snapshot(method, 100).text,
                    McpAuditPolicy.snapshot(tool, 100).text, status.take(32),
                    durationMs.coerceAtLeast(0), input.text, output.text,
                    input.truncated, output.truncated,
                    McpAuditPolicy.snapshot(httpMethod, 12).text,
                    McpAuditPolicy.snapshot(endpoint, 100).text,
                    httpStatus.coerceIn(0, 599),
                    McpAuditPolicy.snapshot(reason, 160).text)
                val all = McpAuditPolicy.today(
                    pruneLocked(c) + entry, System.currentTimeMillis())
                persist(c, all)
                entries.value = all
            }
            Unit
        }

    /** Cap unauthenticated disk writes to one security event per 15 seconds.
     * Public internet scanners must not fill the journal or delay MCP work.
     */
    fun recordDenied(c: Context, httpMethod: String = "GET", endpoint: String = "/mcp",
                     hasAuthorization: Boolean = false,
                     clientHint: String = "Unknown (unverified)", httpStatus: Int = 401) =
        synchronized(lock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastDeniedAtMs >= 15_000 || lastDeniedAtMs == 0L) {
                lastDeniedAtMs = now
                val safeHint = when (clientHint) {
                    "ChatGPT (unverified)", "Claude (unverified)",
                    "Browser (unverified)" -> clientHint
                    else -> "Unknown (unverified)"
                }
                val reason = when (httpStatus) {
                    403 -> "Origin not allowed"
                    else -> if (hasAuthorization) "Credential rejected or expired"
                        else "Access credential missing"
                }
                val safeMethod = httpMethod.takeIf {
                    it in setOf("GET", "POST", "HEAD", "OPTIONS", "DELETE", "PUT", "PATCH")
                } ?: "OTHER"
                record(c, Agent("unauthenticated-" + safeHint.substringBefore(' ').lowercase(),
                    safeHint, "Identity not verified"),
                    "[unauthenticated request; payload withheld]",
                    if (httpStatus == 403) "HTTP 403 Forbidden" else "HTTP 401 Unauthorized",
                    "MCP", "", "Denied", 0,
                    httpMethod = safeMethod, endpoint = "/mcp", httpStatus = httpStatus,
                    reason = reason)
            }
        }

    data class Agent(val id: String, val name: String, val domain: String)
    val DIRECT = Agent("direct-key", "Direct key", "Shared bearer key")
}
