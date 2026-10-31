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
            if (packageUid != null && line.uid != packageUid &&
                (packageName.isNullOrBlank() || !line.raw.contains(packageName, true))) continue
            if (!fingerprints.add(line.raw)) continue
            val event = line.copy(id = nextId++)
            saved.addLast(event)
            result.add(event)
            while (saved.size > capacity) fingerprints.remove(saved.removeFirst().raw)
        }
        return result
    }
    @Synchronized fun snapshot(after: Long = 0, limit: Int = 300): List<LogEvent> =
        saved.asSequence().filter { it.id > after }.take(limit.coerceIn(1, 1000)).toList()
    @Synchronized fun clear() {
        fingerprints.clear()
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
