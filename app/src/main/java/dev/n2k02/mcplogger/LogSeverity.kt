package dev.n2k02.mcplogger

/** Android's Logcat priority codes, including an explicit unknown fallback. */
internal object LogSeverity {
    fun code(value: String): String = when (value.firstOrNull()?.uppercaseChar()) {
        'V' -> "V"; 'D' -> "D"; 'I' -> "I"; 'W' -> "W"; 'E' -> "E"; 'F' -> "F"; else -> "?"
    }
    fun title(value: String): String = when (code(value)) {
        "V" -> "Verbose"; "D" -> "Debug"; "I" -> "Info"
        "W" -> "Warning"; "E" -> "Error"; "F" -> "Fatal"
        else -> "Unknown severity"
    }
}
