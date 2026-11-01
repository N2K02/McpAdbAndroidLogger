package dev.n2k02.mcplogger.core

/**
 * Logcat epoch/uid parser adapted from LogFox's LoggingRepository/LogLineParser.
 * Copyright (C) 2022-2026 Maksim Zoteev. GPL-3.0-or-later.
 * See THIRD_PARTY_NOTICES.md. Parse never throws on malformed or multiline output.
 */
object LogFoxParser {
    private val line = Regex("""^(\d{10,13}\.\d+)\s+(\S+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?):\s?(.*)$""")
    fun parse(input: String): LogEvent? {
        val m = line.matchEntire(input.trim()) ?: return null
        val time = m.groupValues[1].toDoubleOrNull() ?: return null
        return LogEvent(
            id = 0,
            epochMillis = (time * 1000).toLong(),
            uid = m.groupValues[2],
            pid = m.groupValues[3],
            level = m.groupValues[5],
            tag = m.groupValues[6],
            message = m.groupValues[7],
            raw = input,
            tid = m.groupValues[4]
        )
    }
    fun isCrash(log: LogEvent): Boolean = log.tag.contains("crash", true) ||
        log.message.contains("FATAL EXCEPTION") ||
        log.message.contains("Fatal signal") ||
        log.message.contains("ANR in ")
}
