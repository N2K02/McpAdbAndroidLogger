package dev.n2k02.mcplogger.core

/** Filtering does not affect Logcat ingestion or MCP buffer. */
data class LogViewFilter(
    val packageName: String = "",
    val search: String = "",
    val tag: String = "",
    val pid: String = "",
    val minLevel: String = "V",
    val windowMinutes: Int = 0,
    val crashesOnly: Boolean = false,
)

object LogViewPolicy {
    private const val LEVELS = "VDIWEF"

    fun select(events: List<LogEvent>, filter: LogViewFilter,
               packageUids: Map<String, String>, now: Long): List<LogEvent> {
        val minLevel = LEVELS.indexOf(filter.minLevel.firstOrNull() ?: 'V').coerceAtLeast(0)
        val uid = packageUids[filter.packageName]
        return events.filter { event ->
            if (filter.packageName.isNotBlank()) {
                // Never return all-device logs when the selected UID is unknown.
                if (uid == null) {
                    if (!event.raw.contains(filter.packageName, ignoreCase = true)) return@filter false
                } else if (event.uid != uid &&
                    !event.raw.contains(filter.packageName, ignoreCase = true)) return@filter false
            }
            if (filter.windowMinutes > 0 &&
                event.epochMillis < now - filter.windowMinutes.toLong() * 60_000L) return@filter false
            if (filter.crashesOnly && !LogFoxParser.isCrash(event)) return@filter false
            if (LEVELS.indexOf(event.level.firstOrNull() ?: '?') < minLevel) return@filter false
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
            SensitiveLogRedactor.redact(event.message).take(2000)

    fun export(events: List<LogEvent>): String =
        events.joinToString(separator = "\n", postfix = if (events.isNotEmpty()) "\n" else "") {
            single(it)
        }
}
