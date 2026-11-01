package dev.n2k02.mcplogger.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class PerformanceDiagnosticsTest {
    private fun call(name: String, arguments: String = "{}"): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", 1)
            .put("method", "tools/call")
            .put("params", JSONObject().put("name", name)
                .put("arguments", JSONObject(arguments)))

    @Test fun cpuParserOrdersValuesAllowsMulticoreAndFiltersExactProcesses() {
        val raw = """
            Tasks: 300 total
            PID %CPU RES NAME
            42 137.5 420M com.whatsapp
            64 29.0 110M com.vrec.app
            65 0.4 14M com.vrec.app:worker
            66 100.0 2M com.vrec.application
            PID %CPU RES NAME
        """.trimIndent()
        val all = PerformanceDiagnostics.parseCpu(raw)
        assertEquals("ok", all.getString("status"))
        assertEquals("com.whatsapp",
            all.getJSONArray("processes").getJSONObject(0).getString("process"))
        assertEquals(137.5,
            all.getJSONArray("processes").getJSONObject(0).getDouble("cpu_percent"), 0.01)
        val filtered = PerformanceDiagnostics.parseCpu(raw, "com.vrec.app")
        assertEquals(2, filtered.getJSONArray("processes").length())
        assertEquals("com.vrec.app",
            filtered.getJSONArray("processes").getJSONObject(0).getString("process"))
        assertEquals("unavailable_or_no_matching_process",
            PerformanceDiagnostics.parseCpu(raw, "com.mradio.app").getString("status"))
        assertEquals(1,
            PerformanceDiagnostics.parseCpu(raw, limit = 1).getJSONArray("processes").length())
    }

    @Test fun frameParserReportsPercentilesAndGpuLatencyWithoutInventingGpuUsage() {
        val csv = """
            Applications Graphics Acceleration Info:
            ---PROFILEDATA---
            Flags,IntendedVsync,Vsync,SwapBuffers,GpuCompleted,FrameCompleted
            0,1000000000,1001000000,1010000000,1019000000,1020000000
            0,2000000000,2001000000,2008000000,2025000000,2040000000
            1,3000000000,3001000000,3010000000,3030000000,3040000000
            0,4000000000,4001000000,4010000000,4020000000,4025000000
            ---PROFILEDATA---
        """.trimIndent()
        val stats = PerformanceDiagnostics.parseFrames(csv, "com.vrec.app", 16.67)
        assertEquals("ok", stats.getString("status"))
        assertEquals(3, stats.getInt("sample_frames"))
        assertEquals(3, stats.getInt("slow_frames"))
        assertEquals(25.0, stats.getDouble("frame_p50_ms"), 0.01)
        assertEquals(40.0, stats.getDouble("frame_p95_ms"), 0.01)
        assertEquals(17.0, stats.getDouble("gpu_completion_latency_p95_ms"), 0.01)
        assertFalse(stats.has("gpu_utilization_percent"))
        assertEquals("no_ui_framestats",
            PerformanceDiagnostics.parseFrames("No processes found.", "com.bcam.app")
                .getString("status"))
    }

    @Test fun rejectsShellMetacharactersAndInvalidFrameBudgets() {
        assertTrue(PerformanceDiagnostics.validPackage("com.vrec.app"))
        for (p in listOf("", "foo", "com.vrec.app;id", "com.foo && id",
                "com.foo | sh", "com.foo\nid", "../etc/passwd", "com.foo$(id)")) {
            assertFalse("Invalid: " + p, PerformanceDiagnostics.validPackage(p))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PerformanceDiagnostics.parseFrames("", "com.vrec.app", Double.NaN)
        }
    }

    @Test fun MCPDispatchNeverRunsPerformanceReadsWithoutAnExplicitToolCall() {
        val calls = AtomicInteger()
        val reader: (String, JSONObject) -> JSONObject = { name, _ ->
            calls.incrementAndGet()
            JSONObject().put("tool", name)
        }
        LocalMcpProtocol.execute(
            JSONObject("""{"jsonrpc":"2.0","method":"tools/list","id":1}"""),
            performanceReader = reader)
        LocalMcpProtocol.execute(call("get_device_status"), performanceReader = reader)
        assertEquals(0, calls.get())
        for (name in listOf("get_cpu_usage", "get_frame_timing", "get_performance_snapshot")) {
            val args = if (name == "get_frame_timing")
                """{"package_name":"com.vrec.app"}""" else "{}"
            val reply = LocalMcpProtocol.execute(call(name, args),
                performanceReader = reader)!!
            val value = JSONObject(reply.getJSONObject("result").getJSONArray("content")
                .getJSONObject(0).getString("text"))
            assertEquals(name, value.getString("tool"))
        }
        assertEquals(3, calls.get())
        for (command in listOf("top", "sh", "shell", "exec")) {
            assertEquals(-32602, LocalMcpProtocol.execute(call(command),
                performanceReader = reader)!!.getJSONObject("error").getInt("code"))
        }
        assertEquals(-32602, LocalMcpProtocol.execute(
            call("get_frame_timing", """{"package_name":"com.foo;id"}"""),
            performanceReader = reader)!!.getJSONObject("error").getInt("code"))
        assertEquals(3, calls.get())
    }

    @Test fun onlyValidatedFixedReadOnlyShellCommandsAreUsed() {
        val src = listOf(
            File("app/src/main/java/dev/n2k02/mcplogger/core/PerformanceDiagnostics.kt"),
            File("../app/src/main/java/dev/n2k02/mcplogger/core/PerformanceDiagnostics.kt")
        ).first { it.isFile }.readText()
        assertTrue(src.contains("top -b -n 1 -m 200 -o PID,%CPU,RES,NAME"))
        assertTrue(src.contains("dumpsys gfxinfo "))
        assertTrue(src.contains("validatePackage(packageName, true)"))
        assertTrue(src.contains("lock.tryLock()"))
        assertFalse(src.contains("Runtime.getRuntime()"))
        assertFalse(src.contains("ProcessBuilder("))
        val server = listOf(
            File("app/src/main/java/dev/n2k02/mcplogger/core/LocalMcpServer.kt"),
            File("../app/src/main/java/dev/n2k02/mcplogger/core/LocalMcpServer.kt")
        ).first { it.isFile }.readText()
        assertTrue(server.contains("PerformanceDiagnostics.cpu("))
        assertTrue(server.contains("PerformanceDiagnostics.frames("))
        assertTrue(server.contains("PerformanceDiagnostics.summary("))
    }
}
