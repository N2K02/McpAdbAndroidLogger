package dev.n2k02.mcplogger.core

import java.util.LinkedHashSet

/** Bounded, de-duplicated log snapshot for display, export and MCP cursor access. */
class LogBuffer(private val capacity: Int = 2000) {
    private var nextId = 1L
    private val fingerprints = LinkedHashSet<String>()
    private val saved = ArrayDeque<LogEvent>()
    @Synchronized fun add(newLines: List<LogEvent>, packageUid: String?, packageName: String?): List<LogEvent> {
        val result = mutableListOf<LogEvent>()
        for (line in newLines) {
            // Fail closed when a package is selected but its UID is unavailable.
            // Never accidentally return all-device logs for a filtered request.
            if (!packageName.isNullOrBlank()) {
                if (packageUid == null && !line.raw.contains(packageName, ignoreCase = true)) continue
                if (packageUid != null && line.uid != packageUid &&
                    !line.raw.contains(packageName, ignoreCase = true)) continue
            }
            if (!fingerprints.add(line.raw)) continue
            // Retain a bounded dedup window independently of visible rows.
            while (fingerprints.size > maxOf(capacity, 300)) {
                fingerprints.remove(fingerprints.first())
            }
            val event = line.copy(id = nextId++)
            saved.addLast(event)
            result.add(event)
            while (saved.size > capacity) saved.removeFirst()
        }
        return result
    }
    @Synchronized fun snapshot(after: Long = 0, limit: Int = 300): List<LogEvent> =
        saved.asSequence().filter { it.id > after }.take(limit.coerceIn(1, 2000)).toList()
    @Synchronized fun clear() {
        // Preserve fingerprints so the next -d poll does not re-add old lines.
        saved.clear()
    }
}

object SensitiveLogRedactor {
    private val patterns = listOf(
        Regex("""(?i)(authorization|bearer|token|api[_-]?key|password|secret|pairing.code)([=: ]+)([^\s,;]+)"""),
        Regex("""(?i)\b[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}\b"""),
        Regex("""(?i)\b(?:\d[ -]?){13,19}\b""")
    )
    fun redact(source: String): String {
        var result = source
        for ((index, pattern) in patterns.withIndex()) {
            result = if (index == 0) pattern.replace(result, "$1$2[REDACTED]")
                else pattern.replace(result, "[REDACTED]")
        }
        return result
    }
}
