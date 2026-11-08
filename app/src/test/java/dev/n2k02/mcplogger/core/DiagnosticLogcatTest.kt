package dev.n2k02.mcplogger.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticLogcatTest {
    private fun event(id: Long, tag: String, level: String, pid: String = "123") =
        LogEvent(id, 1_700_000_000_000, "2000", pid, level, tag, "message", "")

    @Test fun audioProfileMatchesPriorityAndTag() {
        val options = DiagnosticLogcat.parse(JSONObject("""{"profile":"audio"}"""))
        val events = listOf(event(1, "AudioFlinger", "D"),
            event(2, "AudioFlinger", "W"),
            event(3, "AndroidRuntime", "E"),
            event(4, "Other", "F"))
        assertEquals(listOf(2L, 3L),
            DiagnosticLogcat.select(events, options).map { it.id })
        assertTrue(options.argv().contains("AudioFlinger:W"))
        assertEquals("*:S", options.argv().last())
    }

    @Test fun rejectsCommandInjectionAndUnboundedModes() {
        for (payload in listOf(
            """{"tags":["AudioFlinger:W;id"]}""",
            """{"tags":["*:V"]}""",
            """{"tags":["AudioFlinger:Q"]}""",
            """{"buffers":["all;rm"]}""",
            """{"format":"raw;id"}""",
            """{"mode":"stream"}""",
            """{"pid":-1}""",
            """{"profile":"unknown"}"""
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                DiagnosticLogcat.parse(JSONObject(payload))
            }
        }
    }

    @Test fun snapshotArgvNeverIncludesShellOrClear() {
        val options = DiagnosticLogcat.parse(JSONObject(
            """{"tags":["SEQ-Audio:V"],"buffers":["main","crash"],
                "format":"time","pid":123}"""))
        assertEquals(listOf("logcat", "-d", "-v", "time", "-b", "main",
            "-b", "crash", "--pid", "123", "-s", "SEQ-Audio:V", "*:S"),
            options.argv())
        assertFalse(options.argv().contains("-c"))
    }

    @Test fun sinceAndPidFiltersAreCombined() {
        val options = DiagnosticLogcat.parse(JSONObject(
            """{"since":1700000000000,"pid":123}"""))
        assertEquals(listOf(1L), DiagnosticLogcat.select(
            listOf(event(1, "App", "I"), event(2, "App", "I", "456")),
            options).map { it.id })
    }
}
