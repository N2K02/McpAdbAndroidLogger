package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class SurfaceActivityDiagnosticsTest {
    private val known = setOf("com.android.chrome", "com.android.systemui", "com.example.overlay")
    private val timestats = """
        SurfaceFlinger TimeStats:
        statsStart = 100
        statsEnd = 500
        totalFrames = 500
        layerName = SurfaceView[com.android.chrome/com.android.chrome.MainActivity]#0 PrivateTitle OTP 123
        packageName = com.android.chrome
        totalFrames = 125
        droppedFrames = 6
        averageFPS = 28.50
        layerName = other private title - com.android.chrome
        packageName = INVALID NOT PACKAGED
        totalFrames = 80
        droppedFrames = 2
        averageFPS = 17.5
        layerName = com.android.systemui/NavigationBar#0
        packageName = com.android.systemui
        totalFrames = 4
        droppedFrames = 0
        averageFPS = 1.0
        layerName = com.unknown.private.Messages Activity secret
        packageName = com.unknown.private
        totalFrames = 900
        droppedFrames = 10
    """.trimIndent()

    @Test fun timestatsOnlyReturnVerifiedInstalledPackagesAndNumbers() {
        val entries = SurfaceActivityDiagnostics.parseTimeStats(timestats, known)
        assertEquals(3, entries.size)
        assertEquals("com.android.chrome", entries[0].packageName)
        assertEquals(125L, entries[0].frames)
        assertEquals(6L, entries[0].dropped)
        assertEquals(28.5, entries[0].fps!!, 0.01)
        assertEquals("com.android.chrome", entries[1].packageName)
        assertEquals(80L, entries[1].frames)
        assertEquals("com.android.systemui", entries[2].packageName)
        val ranked = SurfaceActivityDiagnostics.summarize(entries, emptyList(), 5000, 20.0, 20)
        val rows = ranked.getJSONArray("ranked_contributors")
        assertEquals(2, rows.length())
        assertEquals("com.android.chrome", rows.getJSONObject(0).getString("package_name"))
        assertEquals(205L, rows.getJSONObject(0).getLong("timestats_frames"))
        assertEquals(41.0, rows.getJSONObject(0).getDouble("timestats_frames_per_second"), .01)
        assertEquals(20.0, ranked.getDouble("surfaceflinger_process_cpu_percent"), .01)
        assertFalse(ranked.toString().contains("PrivateTitle"))
        assertFalse(ranked.toString().contains("OTP"))
        assertFalse(ranked.toString().contains("com.unknown.private"))
        assertFalse(ranked.toString().contains("Messages Activity"))
    }

    @Test fun traceRowsAreSanitizedAggregatedAndJankCannotExceedFrames() {
        val csv = """
            "name","COUNT(*)","SUM(...)"
            "com.android.chrome",43,3
            "com.android.systemui",12,1
            "com.example.overlay",5,12
            "com.android.chrome.child",100,2
            "banking.app SECRET",3,2
            "com.android.chrome",0,0
        """.trimIndent()
        val parsed = SurfaceActivityDiagnostics.parseTraceRows(csv, known)
        assertEquals(2, parsed.size)
        assertEquals("com.android.chrome", parsed[0].packageName)
        val ranked = SurfaceActivityDiagnostics.summarize(emptyList(), parsed, 4000, null, 5)
        assertEquals("ok", ranked.getString("status"))
        assertEquals("unavailable", ranked.getString("cpu_status"))
        assertTrue(ranked.isNull("surfaceflinger_process_cpu_percent"))
        assertEquals(43L, ranked.getJSONArray("ranked_contributors")
            .getJSONObject(0).getLong("perfetto_frame_events"))
        assertFalse(ranked.toString().contains("SECRET"))
        assertFalse(ranked.toString().contains("com.android.chrome.child"))
    }

    @Test fun cpuStatParserRespectsParenthesizedProcessAndHandlesMissingFrequency() {
        val statFields = MutableList(43) { "0" }
        statFields[0] = "R"
        statFields[11] = "230"
        statFields[12] = "70"
        val begin = "551 (surfaceflinger) " + statFields.joinToString(" ") +
            "\n__HZ__\n100"
        statFields[11] = "245"
        statFields[12] = "85"
        val end = "551 (surfaceflinger) " + statFields.joinToString(" ") +
            "\n__HZ__\n100"
        val before = SurfaceActivityDiagnostics.readCpuStat(begin)
        val after = SurfaceActivityDiagnostics.readCpuStat(end)
        assertNotNull(before)
        assertNotNull(after)
        assertEquals(60.0, SurfaceActivityDiagnostics.processCpu(before, after, 500)!!, .01)
        assertNull(SurfaceActivityDiagnostics.readCpuStat("Permission denied"))
        assertNull(SurfaceActivityDiagnostics.readCpuStat(begin.replace("__HZ__\n100", "")))
        assertNull(SurfaceActivityDiagnostics.processCpu(after, before, 500))
        assertNull(SurfaceActivityDiagnostics.processCpu(before, after, 0))
    }

    @Test fun noActivityAndUnsafeInputNeverInventsCpuOrContributors() {
        val empty = SurfaceActivityDiagnostics.summarize(emptyList(), emptyList(), 5000, null, 20)
        assertEquals("no_attributed_activity", empty.getString("status"))
        assertTrue(empty.isNull("surfaceflinger_process_cpu_percent"))
        assertEquals(0, empty.getJSONArray("ranked_contributors").length())
        assertEquals(0, SurfaceActivityDiagnostics.parseTimeStats(
            "Permission denied - com.android.chrome secret", known).size)
        assertTrue(SurfaceActivityDiagnostics.parseTimeStats(
            timestats.replace("totalFrames = 125", "totalFrames = 9999999999999"), known)
            .none { it.frames > 100000000L })
    }

    @Test fun activityAndPerfettoRemainExplicitBoundedAndNoRawTracesLeaveTheServer() {
        val root = "app/src/main/java/dev/n2k02/mcplogger/"
        fun source(path: String) = listOf(File(root + path), File("../" + root + path))
            .first { it.isFile }.readText()
        val src = source("core/SurfaceActivityDiagnostics.kt")
        val server = source("core/LocalMcpServer.kt")
        val dashboard = source("HomeDashboard.kt")
        assertTrue(src.contains("dumpsys SurfaceFlinger --timestats"))
        assertTrue(src.contains("android.surfaceflinger.frametimeline"))
        assertTrue(src.contains("sched/sched_switch"))
        assertTrue(src.contains("trace_processor_shell"))
        // Perfetto classic CLI -q reads a file. -Q executes a query string.
        assertEquals(2, Regex(" -Q ").findAll(src).count())
        assertFalse(src.contains(" -q "))
        assertTrue(src.contains("captureMs < seconds * 1000L"))
        assertTrue(src.contains("timestats_unavailable"))
        assertTrue(src.contains("timestats_status"))
        assertTrue(src.contains("NonCancellable"))
        assertTrue(src.contains("rm -f"))
        assertTrue(src.contains("seconds in 3..MAX_SECONDS"))
        assertTrue(src.contains("AdbCoordinator.verified.value"))
        assertFalse(src.contains("ProcessBuilder("))
        assertFalse(src.contains("Runtime.getRuntime()"))
        assertTrue(server.contains("SurfaceActivityDiagnostics.activity("))
        assertTrue(server.contains("SurfaceActivityDiagnostics.perfetto("))
        assertTrue(dashboard.contains("Render activity"))
        assertTrue(dashboard.contains("Deep Perfetto"))
    }

    @Test fun bothMcpToolsExposeBoundedSchemaAndDispatchOnlyOnCall() {
        val names = listOf("get_surfaceflinger_activity", "get_surfaceflinger_perfetto")
        var called = 0
        val reader: (String, JSONObject) -> JSONObject = { name, args ->
            called++
            assertTrue(name in names)
            assertEquals(4, args.getInt("duration_seconds"))
            assertEquals(6, args.getInt("limit"))
            JSONObject().put("status", "mocked")
        }
        val tools = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":1}"""),
            performanceReader = reader)!!
            .getJSONObject("result").getJSONArray("tools")
        assertEquals(17, tools.length())
        assertEquals(0, called)
        for (name in names) {
            val schema = (0 until tools.length()).map { tools.getJSONObject(it) }
                .single { it.getString("name") == name }
                .getJSONObject("inputSchema")
            assertEquals("integer", schema.getJSONObject("properties")
                .getJSONObject("duration_seconds").getString("type"))
            assertEquals(false, schema.getBoolean("additionalProperties"))
            val request = JSONObject().put("jsonrpc", "2.0").put("id", 1)
                .put("method", "tools/call")
                .put("params", JSONObject().put("name", name)
                    .put("arguments", JSONObject().put("duration_seconds", 4).put("limit", 6)))
            assertFalse(LocalMcpProtocol.execute(request, performanceReader = reader)!!
                .has("error"))
            assertEquals(-32602, LocalMcpProtocol.execute(
                JSONObject(request.toString()).apply {
                    getJSONObject("params").getJSONObject("arguments")
                        .put("duration_seconds", 99)
                }, performanceReader = reader)!!.getJSONObject("error").getInt("code"))
        }
        assertEquals(2, called)
    }
}
