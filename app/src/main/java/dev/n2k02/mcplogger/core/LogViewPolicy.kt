package dev.n2k02.mcplogger.core

/** Filtering does not affect Logcat ingestion or MCP buffer. */
data class LogViewFilter(
    val packageName: String = "",
    val search: String = "",
    val tag: String = "",
    val pid: String = "",
    val selectedLevels: Set<String> = emptySet(),
    val windowMinutes: Int = 0,
    val crashesOnly: Boolean = false,
)

object LogViewPolicy {
    private const val LEVELS = "VDIWEF"
    val levelOptions: List<Pair<String, String>> = listOf(
        "V" to "Verbose", "D" to "Debug", "I" to "Info",
        "W" to "Warning", "E" to "Error", "F" to "Fatal")

    fun toggleLevel(filter: LogViewFilter, code: String): LogViewFilter {
        if (code !in LEVELS.map(Char::toString)) return filter
        val levels = if (code in filter.selectedLevels) filter.selectedLevels - code
            else filter.selectedLevels + code
        return filter.copy(selectedLevels = levels)
    }

    fun levelSummary(filter: LogViewFilter): String = when (filter.selectedLevels.size) {
        0 -> "All"
        1 -> levelOptions.first { it.first in filter.selectedLevels }.second
        else -> "${filter.selectedLevels.size} selected"
    }

    fun select(events: List<LogEvent>, filter: LogViewFilter,
               packageUids: Map<String, String>, now: Long): List<LogEvent> {
        val selected = filter.selectedLevels
        val uid = packageUids[filter.packageName]
        return events.filter { event ->
            if (filter.packageName.isNotBlank()) {
                // Strict UID match. A log message mentioning a package is not
                // evidence that it originated from the selected application.
                if (uid == null || event.uid != uid) return@filter false
            }
            if (filter.windowMinutes > 0 &&
                event.epochMillis < now - filter.windowMinutes.toLong() * 60_000L) return@filter false
            if (filter.crashesOnly && !LogFoxParser.isCrash(event)) return@filter false
            if (selected.isNotEmpty() && event.level !in selected) return@filter false
            if (filter.pid.isNotBlank() && !event.pid.contains(filter.pid.trim())) return@filter false
            if (filter.tag.isNotBlank() && !event.tag.contains(filter.tag.trim(), true)) return@filter false
            val q = filter.search.trim()
            if (q.isNotEmpty() && !event.tag.contains(q, true) &&
                !event.message.contains(q, true) && !event.pid.contains(q)) return@filter false
            true
        }
    }

    fun reset(): LogViewFilter = LogViewFilter()
}

/** Redacted output for row copy, filtered copy and Storage Access Framework export. */
object LogExportFormatter {
    fun single(event: LogEvent): String =
        event.epochMillis.toString() + "  " + event.uid + "  " + event.pid + "  " + event.level + "  " +
            SensitiveLogRedactor.redact(event.tag).take(120) + ": " +
            SensitiveLogRedactor.redact(event.message)

    fun export(events: List<LogEvent>): String =
        events.joinToString(separator = "\n", postfix = if (events.isNotEmpty()) "\n" else "") {
            single(it)
        }
}
