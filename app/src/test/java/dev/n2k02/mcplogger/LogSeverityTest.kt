package dev.n2k02.mcplogger

import org.junit.Assert.*
import org.junit.Test

class LogSeverityTest {
    @Test fun allAndroidLogcatLevelsHaveReadableLabels() {
        val expected = mapOf("V" to "Verbose", "D" to "Debug", "I" to "Info",
            "W" to "Warning", "E" to "Error", "F" to "Fatal")
        for ((code, name) in expected) {
            assertEquals(name, LogSeverity.title(code))
            assertEquals(code, LogSeverity.code(code))
            assertEquals(name, LogSeverity.title(code.lowercase()))
        }
        assertEquals("Unknown severity", LogSeverity.title("?"))
        assertEquals("?", LogSeverity.code(""))
    }
}
