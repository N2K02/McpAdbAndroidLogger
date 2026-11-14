package dev.n2k02.mcplogger.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticLogcatTest {
    private fun event(id: Long, tag: String, level: String, pid: String = "123") =
        LogEvent(id, 1_700_000_000_000, "2000", pid, level, tag, "message", "")

    @Test fun bareTagsAndExplicitPrioritiesAreBothSafe() {
        val options = DiagnosticLogcat.parse(JSONObject("""{"tags":["scrcpy","AudioRecord:W"]}"""))
        assertEquals(listOf("scrcpy" to "V", "AudioRecord" to "W"), options.tags)
        assertTrue(DiagnosticLogcat.captureCommand(options).contains("scrcpy:V"))
        for (tag in listOf("scrcpy;id", "scrcpy:", "scrcpy:V:W", "*", "scrcpy V")) {
            assertThrows(IllegalArgumentException::class.java) {
                DiagnosticLogcat.parse(JSONObject().put("tags", org.json.JSONArray(listOf(tag))))
            }
        }
        assertTrue(DiagnosticLogcat.parse(JSONObject("""{"profile":"vrec"}""")).tags.contains("scrcpy" to "I"))
    }

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

    @Test fun deviceObservedAudioAndThermalTagsHaveBoundedPresets() {
        val audio = DiagnosticLogcat.parse(JSONObject("""{"profile":"audio"}"""))
        assertTrue(audio.tags.contains("StHal" to "I"))
        assertTrue(audio.tags.contains("BTAudioSessionAidl" to "D"))
        assertTrue(audio.tags.size <= 48)
        val battery = DiagnosticLogcat.parse(JSONObject("""{"profile":"battery"}"""))
        assertTrue(battery.tags.contains("pixel-thermal" to "I"))
        assertTrue(battery.tags.size <= 48)
        val log = LogEvent(17L, 1_700_000_000_000L, "1000", "111",
            "I", "pixel-thermal", "VIRTUAL-SKIN:39", "")
        assertEquals(listOf(17L), DiagnosticLogcat.select(listOf(log), battery).map { it.id })
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

    @Test fun nativeCapturePreservesParserFormatAndBounds() {
        val options = DiagnosticLogcat.parse(JSONObject(
            """{"profile":"audio","buffers":["main","crash"],"pid":123}"""))
        val command = DiagnosticLogcat.captureCommand(options)
        assertTrue(command.contains("logcat -b main -b crash"))
        assertTrue(command.contains("-v uid -v epoch -d -t 2000"))
        assertTrue(command.contains("--pid 123"))
        assertTrue(command.contains("AudioFlinger:W"))
        assertFalse(command.contains(" -c"))
        assertFalse(command.contains(";"))
    }

    @Test fun sinceAndPidFiltersAreCombined() {
        val options = DiagnosticLogcat.parse(JSONObject(
            """{"since":1700000000000,"pid":123}"""))
        assertEquals(listOf(1L), DiagnosticLogcat.select(
            listOf(event(1, "App", "I"), event(2, "App", "I", "456")),
            options).map { it.id })
    }
}
