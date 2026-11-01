package dev.n2k02.mcplogger.core

import org.json.JSONArray
import org.json.JSONObject

/** Sanitized, bounded snapshots of requests and data sent in MCP responses. */
internal object McpAuditPolicy {
    const val MAX_REQUEST_CHARS = 10_000
    const val MAX_RESPONSE_CHARS = 25_000
    const val KEEP = 160
    private val secretName = Regex(
        "(?i)(authorization|password|passphrase|secret|token|api[_-]?key|" +
            "client[_-]?secret|cookie|credential|pairing|pin|private[_-]?key|" +
            "code_verifier|auth[_-]?code|refresh|session|key$)")
    private const val REDACTED = "[REDACTED]"

    private fun safe(value: Any?, depth: Int = 0): Any {
        if (depth >= 16) return "[nested content omitted]"
        return when (value) {
            is JSONObject -> JSONObject().also { out ->
                value.keys().forEach { name ->
                    out.put(name, if (secretName.containsMatchIn(name)) REDACTED
                        else safe(value.opt(name), depth + 1))
                }
            }
            is JSONArray -> JSONArray().also { out ->
                for (i in 0 until value.length()) out.put(safe(value.opt(i), depth + 1))
            }
            is String -> SensitiveLogRedactor.redact(value)
            null -> JSONObject.NULL
            else -> value
        }
    }

    data class Snapshot(val text: String, val truncated: Boolean)
    fun snapshot(source: String, max: Int): Snapshot {
        val clean = try {
            val parsed = source.trim()
            val value: Any = when {
                parsed.startsWith("{") -> JSONObject(parsed)
                parsed.startsWith("[") -> JSONArray(parsed)
                else -> source
            }
            val result = safe(value)
            SensitiveLogRedactor.redact(if (result is String) result else result.toString())
        } catch (_: Exception) {
            SensitiveLogRedactor.redact(source)
        }
        val bounded = max.coerceIn(0, 100_000)
        return if (clean.length > bounded)
            Snapshot(clean.take(bounded) + "\n[Audit preview truncated; original response unchanged]", true)
        else Snapshot(clean, false)
    }

    /**
     * Hints from Origin/User-Agent are never verified agent identities.
     * Whitelist labels only; never persist arbitrary headers or their values.
     */
    fun clientHint(origin: String?, userAgent: String?): String {
        val o = origin.orEmpty().lowercase()
        val ua = userAgent.orEmpty().lowercase()
        return when {
            o == "https://chatgpt.com" || o == "https://chat.openai.com" ||
                ua.contains("chatgpt") || ua.contains("openai") -> "ChatGPT (unverified)"
            o == "https://claude.ai" || ua.contains("claude") || ua.contains("anthropic") ->
                "Claude (unverified)"
            ua.contains("mozilla/") || ua.contains("chrome/") || ua.contains("safari/") ->
                "Browser (unverified)"
            else -> "Unknown (unverified)"
        }
    }

    data class Filter(
        val agentId: String = "",
        val query: String = "",
        val tool: String = "",
        val status: String = "",
        val minutes: Int = 0
    )
    fun select(entries: List<McpAuditEntry>, filter: Filter, now: Long): List<McpAuditEntry> =
        entries.filter { e ->
            (filter.agentId.isEmpty() || e.agentId == filter.agentId) &&
            (filter.tool.isEmpty() || e.tool == filter.tool) &&
            (filter.status.isEmpty() || e.status == filter.status) &&
            (filter.minutes <= 0 || e.timestamp >= now - filter.minutes.toLong() * 60_000) &&
            (filter.query.isBlank() || listOf(e.agentName, e.domain, e.method, e.tool,
                e.request, e.response, e.status, e.httpMethod, e.endpoint, e.reason,
                e.httpStatus.toString()).any { it.contains(filter.query.trim(), true) })
        }

    /**
     * Agent-facing subset of the local audit journal: newest first, bounded, and
     * re-sanitized at read time. Response previews are optional to minimize
     * cross-agent data exposure. Cursor references the oldest returned entry.
     */
    fun agentPage(entries: List<McpAuditEntry>, requestedLimit: Int = 20,
                  beforeId: String = "", includePayloads: Boolean = false): JSONObject {
        val prefix = if (beforeId.isBlank()) entries else {
            val cursorIndex = entries.indexOfFirst { it.id == beforeId }
            require(cursorIndex >= 0) { "Unknown or expired MCP audit cursor" }
            entries.take(cursorIndex)
        }
        val page = prefix.takeLast(requestedLimit.coerceIn(1, 25)).asReversed()
        val items = JSONArray()
        page.forEach { e ->
            val row = JSONObject()
                .put("id", e.id)
                .put("time", e.timestamp)
                .put("agent", snapshot(e.agentName, 100).text)
                .put("domain", snapshot(e.domain, 150).text)
                .put("method", snapshot(e.method, 100).text)
                .put("tool", snapshot(e.tool, 100).text)
                .put("status", snapshot(e.status, 32).text)
                .put("http_method", snapshot(e.httpMethod, 12).text)
                .put("endpoint", snapshot(e.endpoint, 100).text)
                .put("http_status", e.httpStatus)
                .put("duration_ms", e.durationMs)
                .put("reason", snapshot(e.reason, 160).text)
            if (includePayloads) {
                row.put("request", snapshot(e.request, 2500).text)
                row.put("response", snapshot(e.response, 5000).text)
            }
            items.put(row)
        }
        return JSONObject().put("entries", items)
            .put("next_before_id", if (prefix.size > page.size)
                page.last().id else JSONObject.NULL)
            .put("payloads_included", includePayloads)
    }

    fun export(entries: List<McpAuditEntry>): String =
        entries.joinToString("\n\n") { e ->
            "Time: " + e.timestamp + " (" +
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(e.timestamp)) +
                ")\nAgent: " + e.agentName + " (" + e.domain + ")\n" +
                "Method: " + e.method + "\nTool: " + e.tool + "\n" +
                "Status: " + e.status + " · " + e.durationMs + " ms\n" +
                (if (e.httpMethod.isNotBlank()) "HTTP: " + e.httpMethod + " " + e.endpoint +
                    " · " + e.httpStatus + "\n" else "") +
                (if (e.reason.isNotBlank()) "Reason: " + e.reason + "\n" else "") +
                "Request [redacted" + (if (e.requestTruncated) ", truncated" else "") + "]:\n" + e.request + "\n" +
                "Response [redacted" + (if (e.responseTruncated) ", truncated" else "") + "]:\n" + e.response
        }
}

internal data class McpAuditEntry(
    val id: String,
    val timestamp: Long,
    val agentId: String,
    val agentName: String,
    val domain: String,
    val method: String,
    val tool: String,
    val status: String,
    val durationMs: Long,
    val request: String,
    val response: String,
    val requestTruncated: Boolean,
    val responseTruncated: Boolean,
    val httpMethod: String = "",
    val endpoint: String = "",
    val httpStatus: Int = 0,
    val reason: String = ""
) {
    fun toJson() = JSONObject()
        .put("id", id).put("time", timestamp).put("agent_id", agentId)
        .put("agent_name", agentName).put("domain", domain)
        .put("method", method).put("tool", tool).put("status", status)
        .put("duration_ms", durationMs).put("request", request).put("response", response)
        .put("request_truncated", requestTruncated)
        .put("response_truncated", responseTruncated)
        .put("http_method", httpMethod).put("endpoint", endpoint)
        .put("http_status", httpStatus).put("reason", reason)

    companion object {
        fun fromJson(j: JSONObject) = McpAuditEntry(
            j.getString("id"), j.getLong("time"), j.getString("agent_id"),
            j.getString("agent_name"), j.optString("domain"), j.optString("method"),
            j.optString("tool"), j.optString("status"), j.optLong("duration_ms"),
            j.optString("request"), j.optString("response"),
            j.optBoolean("request_truncated"), j.optBoolean("response_truncated"),
            j.optString("http_method"), j.optString("endpoint"),
            j.optInt("http_status"), j.optString("reason"))
    }
}
