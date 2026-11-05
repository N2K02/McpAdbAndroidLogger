package dev.n2k02.mcplogger.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class DeviceDiagnosticsTest {
    private fun call(name: String, arguments: String = "{}"): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", 11)
            .put("method", "tools/call")
            .put("params", JSONObject().put("name", name)
                .put("arguments", JSONObject(arguments)))

    private val checkinStart = """
        9,0,i,uid,10123,com.example.worker
        9,0,i,uid,10124,com.example.other
        9,0,i,uid,10125,malicious;secret
        9,0,l,bt,1,80000,70000,100000,80000,1793000000000,60000,50000
        9,10123,l,wl,SENSITIVE_WAKELOCK_NAME,0,f,0,5000,p,4,0,w,0
        9,10123,l,wl,OTHER_PRIVATE_TAG,0,f,0,3000,p,1,0,w,0
        9,10124,l,wl,tag,0,f,0,2000,p,2,0,w,0
        9,0,l,pwi,uid,10123,15.25
        9,0,l,pwi,uid,10124,5.5
        ignored
    """.trimIndent()

    @Test fun batteryStateParsesNumericalFactsButNotSensitiveStrings() {
        val raw = """
            Current Battery Service state:
              AC powered: false
              USB powered: false
              Wireless powered: false
              status: 3
              health: 2
              plugged: 0
              level: 7
              scale: 100
              voltage: 3764
              temperature: 413
        """.trimIndent()
        val state = DeviceDiagnostics.parseBattery(raw)
        assertEquals("ok", state.getString("status"))
        assertEquals(7.0, state.getDouble("level_percent"), 0.01)
        assertEquals(41.3, state.getDouble("temperature_c"), 0.01)
        assertFalse(state.getBoolean("is_plugged"))
        assertEquals(3, state.getInt("battery_status_code"))
        assertEquals("unavailable", DeviceDiagnostics.parseBattery("permission denied")
            .getString("status"))
    }

    @Test fun memoryExposesAvailableRamSwapAndPressureWithoutFabricatingMetrics() {
        val data = """
            MemTotal:       8192000 kB
            MemFree:         512000 kB
            MemAvailable:   2048000 kB
            SwapTotal:      4194304 kB
            SwapFree:       3145728 kB
            __MCP_PSI__
            some avg10=12.50 avg60=3.25 avg300=1.00 total=12345
            full avg10=0.25 avg60=0.15 avg300=0.10 total=100
        """.trimIndent()
        val result = DeviceDiagnostics.parseMemory(data)
        assertEquals(25.0, result.getDouble("available_ram_percent"), 0.01)
        assertEquals(1048576L, result.getLong("swap_used_kib"))
        assertEquals(12.5, result.getJSONObject("psi")
            .getJSONObject("some").getDouble("avg10_percent"), 0.01)
        assertEquals(0.25, result.getJSONObject("psi")
            .getJSONObject("full").getDouble("avg10_percent"), 0.01)
        val missing = DeviceDiagnostics.parseMemory("MemTotal: 4096 kB")
        assertEquals("unavailable", missing.getString("psi_status"))
        assertTrue(missing.isNull("available_ram_kib"))
    }

    @Test fun psiFailuresReportWhyUnavailableWithoutInventingStallPercentages() {
        val denied = DeviceDiagnostics.parseMemory("""
            MemTotal: 8192000 kB
            MemAvailable: 2048000 kB
            SwapTotal: 4096000 kB
            SwapFree: 1024000 kB
            __MCP_PSI__
            cat: /proc/pressure/memory: Permission denied
        """.trimIndent())
        assertEquals("ok", denied.getString("status"))
        assertEquals("permission_denied", denied.getString("psi_status"))
        assertEquals(0, denied.getJSONObject("psi").length())
        assertEquals(25.0, denied.getDouble("available_ram_percent"), 0.01)
        assertEquals(3072000L, denied.getLong("swap_used_kib"))
        assertFalse(denied.toString().contains("/proc/pressure/memory: Permission denied"))

        val unsupported = DeviceDiagnostics.parseMemory("""
            MemTotal: 8192000 kB
            __MCP_PSI__
            cat: /proc/pressure/memory: No such file or directory
        """.trimIndent())
        assertEquals("not_supported", unsupported.getString("psi_status"))
        assertEquals("unavailable",
            DeviceDiagnostics.parseMemory("MemTotal: 100 kB")
                .getString("psi_status"))
    }

    @Test fun modernWakelockRowsUseAggregatedUidCountersAndSafeTagFallback() {
        val modern = """
            9,0,i,uid,10123,com.example.worker
            9,10123,l,wl,SECRET_TAG,0,f,0,-1,-1,-1,5000,p,4,0,0,5000,0,bp,0,-1,-1,-1,0,w,0,-1,-1,-1
            9,10123,l,wl,ANOTHER_SECRET,0,f,0,-1,-1,-1,3000,p,1,0,0,3000,0,bp,0,-1,-1,-1,0,w,0,-1,-1,-1
            9,10123,l,awl,6500,4500
            9,10124,l,wl,FALLBACK_TAG,0,f,0,-1,-1,-1,2700,p,3,0,0,2700
            9,10125,l,awl,900,600
        """.trimIndent()
        val parsed = DeviceDiagnostics.parseCheckin(modern)
        assertEquals(6500L, parsed.partialWakelockMs[10123])
        assertEquals(2700L, parsed.partialWakelockMs[10124])
        assertEquals(900L, parsed.partialWakelockMs[10125])
        val view = DeviceDiagnostics.parseWakelocks("", parsed)
        assertEquals("ok", view.getString("status"))
        assertEquals("ok", view.getString("cumulative_counter_status"))
        assertFalse(view.toString().contains("SECRET_TAG"))
        assertFalse(view.toString().contains("FALLBACK_TAG"))
        assertEquals(3, view.getJSONArray("wakelocks").length())

        val baseline = DeviceDiagnostics.parseCheckin("""
            9,10123,l,awl,1000,500
            9,10125,l,awl,300,100
        """.trimIndent())
        val comparison = DeviceDiagnostics.screenOffDelta(baseline, parsed, 10000L)
        assertEquals("ok", comparison.getString("status"))
        assertEquals(5500L, comparison.getJSONArray("wakelock_deltas")
            .getJSONObject(0).getLong("partial_hold_ms_delta"))
    }

    @Test fun checkinMapsUidAndNeverLeaksWakelockTags() {
        val parsed = DeviceDiagnostics.parseCheckin(checkinStart)
        assertEquals("com.example.worker", parsed.packages[10123])
        val shared = DeviceDiagnostics.parseCheckin(checkinStart +
            "\n9,0,i,uid,10123,com.example.another")
        assertEquals("uid:10123 (shared)", shared.packages[10123])
        assertNull(parsed.packages[10125])
        assertEquals(8000L, parsed.partialWakelockMs[10123])
        assertEquals(2000L, parsed.partialWakelockMs[10124])
        assertEquals(15.25, parsed.estimatedMah[10123]!!, 0.001)
        assertEquals(60000L, parsed.screenOffRealtimeMs)
        val power = """
           Wake Locks: size=2
             PARTIAL_WAKE_LOCK 'SECRET_TAG' ACQ=-1s (uid=10123 pid=123)
             PARTIAL_WAKE_LOCK 'ANOTHER_SECRET' ACQ=-2s (uid=10123 pid=456)
        """.trimIndent()
        val result = DeviceDiagnostics.parseWakelocks(power, parsed)
        assertEquals(2, result.getInt("active_partial_total"))
        assertEquals(2, result.getJSONArray("wakelocks")
            .getJSONObject(0).getInt("active_partial_count"))
        assertFalse(result.toString().contains("SECRET_TAG"))
        assertFalse(result.toString().contains("SENSITIVE_WAKELOCK_NAME"))
    }

    @Test fun idleComparisonUsesRealCountersAndRejectsResets() {
        val start = DeviceDiagnostics.parseCheckin(checkinStart)
        val end = DeviceDiagnostics.parseCheckin(checkinStart.replace(
            "60000,50000", "90000,80000").replace("80000,70000", "110000,100000")
            .replace("5000,p", "9000,p").replace("3000,p", "5000,p")
            .replace("2000,p", "4500,p"))
        val report = DeviceDiagnostics.screenOffDelta(start, end, 45000)
        assertEquals("ok", report.getString("status"))
        assertEquals(45000L, report.getLong("elapsed_ms"))
        assertEquals(30000L, report.getLong("screen_off_ms_delta"))
        assertEquals(6000L, report.getJSONArray("wakelock_deltas")
            .getJSONObject(0).getLong("partial_hold_ms_delta"))
        val reset = DeviceDiagnostics.parseCheckin(checkinStart.replace(
            "1793000000000", "1794000000000"))
        assertEquals("counters_reset", DeviceDiagnostics.screenOffDelta(
            start, reset, 120000).getString("status"))
        val noCounter = DeviceDiagnostics.screenOffDelta(
            DeviceDiagnostics.parseCheckin(""), DeviceDiagnostics.parseCheckin(""), 1000)
        assertTrue(noCounter.isNull("screen_off_ms_delta"))
    }

    @Test fun mcpHealthCallsOnlyExecuteOnExplicitDispatchAndRejectArbitraryTools() {
        val count = AtomicInteger(0)
        val reader: (String, JSONObject) -> JSONObject = { name, args ->
            count.incrementAndGet()
            JSONObject().put("name", name).put("limit", args.optInt("limit", 0))
        }
        val list = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""),
            performanceReader = reader)!!
            .getJSONObject("result").getJSONArray("tools")
        val available = (0 until list.length()).map {
            list.getJSONObject(it).getString("name")
        }
        val names = listOf("get_battery_diagnostics", "get_memory_pressure",
            "get_wakelock_usage", "get_screen_off_baseline", "get_screen_off_report",
            "get_surfaceflinger_layers")
        assertTrue(available.containsAll(names))
        assertEquals(0, count.get())
        LocalMcpProtocol.execute(call("get_device_status"), performanceReader = reader)
        assertEquals(0, count.get())
        for (name in names) {
            val response = LocalMcpProtocol.execute(call(name), performanceReader = reader)!!
            assertEquals(name, JSONObject(response.getJSONObject("result")
                .getJSONArray("content").getJSONObject(0).getString("text"))
                .getString("name"))
        }
        assertEquals(names.size, count.get())
        for (name in listOf("exec", "shell", "cat", "dumpsys")) {
            assertEquals(-32602, LocalMcpProtocol.execute(call(name),
                performanceReader = reader)!!.getJSONObject("error").getInt("code"))
        }
        assertEquals(names.size, count.get())
    }

    @Test fun diagnosticsNeverStartBackgroundPollerAndBaselineClearsOnStop() {
        val root = "app/src/main/java/dev/n2k02/mcplogger/"
        fun source(p: String) = listOf(File(root + p), File("../" + root + p))
            .first { it.isFile }.readText()
        val diag = source("core/DeviceDiagnostics.kt")
        val server = source("core/LocalMcpServer.kt")
        assertTrue(diag.contains("lock.tryLock()"))
        assertTrue(diag.contains("dumpsys batterystats --charged --checkin"))
        assertTrue(diag.contains("/proc/pressure/memory"))
        assertTrue(diag.contains("dumpsys power"))
        assertFalse(diag.contains("ProcessBuilder("))
        assertFalse(diag.contains("Runtime.getRuntime()"))
        assertFalse(diag.contains("batterystats --reset"))
        assertTrue(server.contains("DeviceDiagnostics.clearBaseline()"))
        for (name in listOf("battery", "memory", "wakelocks", "beginScreenOff", "reportScreenOff"))
            assertTrue(server.contains("DeviceDiagnostics.$name("))
    }
}
