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

    fun reload(c: Context) = synchronized(lock) { entries.value = read(c) }

    fun clear(c: Context) = synchronized(lock) {
        entries.value = emptyList()
        file(c).delete()
    }

    fun record(c: Context, identity: Agent, request: String, response: String,
               method: String, tool: String, status: String, durationMs: Long) =
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
                    input.truncated, output.truncated)
                val all = (read(c) + entry).takeLast(McpAuditPolicy.KEEP)
                val json = JSONArray()
                all.forEach { json.put(it.toJson()) }
                val outputFile = file(c)
                val stream = outputFile.startWrite()
                try {
                    stream.write(json.toString().toByteArray(Charsets.UTF_8))
                    outputFile.finishWrite(stream)
                } catch (e: Exception) {
                    outputFile.failWrite(stream)
                    throw e
                }
                entries.value = all
            }
            Unit
        }

    /** Cap unauthenticated disk writes to one security event per 15 seconds.
     * Public internet scanners must not fill the journal or delay MCP work.
     */
    fun recordDenied(c: Context) = synchronized(lock) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDeniedAtMs >= 15_000 || lastDeniedAtMs == 0L) {
            lastDeniedAtMs = now
            record(c, Agent("unauthenticated", "Unauthenticated", "Unknown"),
                "[unauthenticated request; payload withheld]",
                "HTTP 401 Unauthorized", "MCP", "", "Denied", 0)
        }
    }

    data class Agent(val id: String, val name: String, val domain: String)
    val DIRECT = Agent("direct-key", "Direct key", "Shared bearer key")
}
