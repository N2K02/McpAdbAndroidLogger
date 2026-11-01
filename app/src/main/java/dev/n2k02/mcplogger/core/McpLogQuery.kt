package dev.n2k02.mcplogger.core

/**
 * Read-only MCP package filtering. The client can narrow a query, but never
 * change Android Logcat capture or the on-device Logs screen's saved filter.
 *
 * Package identity is verified against the ADB shell's package -> UID snapshot.
 * Missing/unknown package mappings fail closed: mentioning a package name in a
 * log message does not prove that the log originated from that package.
 */
internal object McpLogQuery {
    fun select(
        events: List<LogEvent>,
        tool: String,
        packageName: String,
        packageUids: Map<String, String>,
        query: String = "",
        level: String = "",
        limit: Int = 100,
    ): List<LogEvent> {
        require(tool in setOf("get_live_logs", "search_logs", "get_crashes", "get_anrs"))
        val name = packageName.trim()
        require(name.length <= 255)
        require(name.isEmpty() || name.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]*")))
        val selectedUid = if (name.isEmpty()) null else {
            // Unknown/unchecked packages must not silently return all-device logs.
            packageUids[name] ?: return emptyList()
        }
        return events.filter { event ->
            val kindMatches = when (tool) {
                "get_crashes" -> event.message.contains("FATAL EXCEPTION", true) ||
                    event.message.contains("Fatal signal", true) ||
                    event.tag.contains("crash", true)
                "get_anrs" -> event.message.contains("ANR in ", true) ||
                    event.tag.contains("am_anr", true)
                else -> true
            }
            (selectedUid == null || event.uid == selectedUid) &&
                kindMatches &&
                (tool != "get_live_logs" || level.isEmpty() ||
                    event.level.equals(level, true)) &&
                (query.isBlank() || event.tag.contains(query, true) ||
                    event.message.contains(query, true))
        }.takeLast(limit.coerceIn(1, 500))
    }
}
