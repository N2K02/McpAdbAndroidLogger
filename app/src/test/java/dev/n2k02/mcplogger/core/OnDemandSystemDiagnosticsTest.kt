package dev.n2k02.mcplogger.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnDemandSystemDiagnosticsTest {
    private val pkg = "com.example.player"

    @Test fun servicesOnlyCountExactPackageRecords() {
        val rows = """
            ServiceRecord{123 u0 com.example.player/.PlayerService}
            ServiceRecord{124 u0 com.example.player2/.OtherService}
            ServiceRecord{125 u0 com.example.player/.RecordingService} isForeground=true
            com.example.player executing=true
        """.trimIndent()
        val result = OnDemandSystemDiagnostics.parseServices(pkg, rows)
        assertEquals(2, result.getInt("service_record_matches"))
        assertEquals(1, result.getInt("foreground_hints"))
        assertEquals(1, result.getInt("running_hints"))
        assertFalse(result.toString().contains("RecordingService"))
        assertFalse(result.toString().contains("OtherService"))
    }

    @Test fun audioSummaryDoesNotReturnOtherPackagesOrRawDumps() {
        val result = OnDemandSystemDiagnostics.parseAudio(pkg, """
            focus for com.example.player
            recording com.example.player
            playback for com.example.player2
            player for com.example.player
            private-token=secret
        """.trimIndent())
        assertEquals(1, result.getInt("audio_focus_mentions"))
        assertEquals(1, result.getInt("recording_mentions"))
        assertEquals(1, result.getInt("playback_mentions"))
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("player2"))
    }

    @Test fun jobHintsAreOnlyAssociatedWithExactPackage() {
        val result = OnDemandSystemDiagnostics.parseJobs(pkg, """
            JobStatus{123 com.example.player} pending
            JobStatus{124 com.example.player2} running
            JobStatus{125 com.example.player} quota restricted
        """.trimIndent())
        assertEquals(2, result.getInt("job_record_matches"))
        assertEquals(1, result.getInt("pending_hints"))
        assertEquals(1, result.getInt("restriction_hints"))
        assertEquals(0, result.getInt("running_hints"))
    }

    @Test fun thermalReportHasStatusAndLimitedSafeTemperatures() {
        val result = OnDemandSystemDiagnostics.parseThermal("""
            Thermal Status: 3
            Temperature{mName=cpu0, mValue=52.5, mType=0}
            Temperature{mName=gpu0, mValue=58.0, mType=1}
            serial: private-sensor-123
        """.trimIndent())
        assertEquals("ok", result.getString("status"))
        assertEquals(3, result.getInt("thermal_status_code"))
        assertEquals(2, result.getJSONArray("temperatures").length())
        assertFalse(result.toString().contains("private-sensor-123"))
        assertEquals("unavailable",
            OnDemandSystemDiagnostics.parseThermal("Unsupported")
                .getString("status"))
    }

    @Test fun allFourMcpToolsRequireExplicitCallsAndOnlyValidatedPackages() {
        val names = listOf("get_app_services", "get_app_audio_state", "get_app_jobs",
            "get_thermal_state")
        val tools = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":1}"""))!!
            .getJSONObject("result").getJSONArray("tools")
        assertEquals(25, tools.length())
        val catalog = (0 until tools.length()).map { tools.getJSONObject(it) }
        for (name in names) {
            val schema = catalog.single { it.getString("name") == name }
                .getJSONObject("inputSchema")
            assertFalse(schema.getBoolean("additionalProperties"))
            val needsPackage = name != "get_thermal_state"
            if (needsPackage) assertEquals("package_name",
                schema.getJSONArray("required").getString(0))
            else assertFalse(schema.has("required"))

            var count = 0
            val args = if (needsPackage) JSONObject().put("package_name", pkg)
                else JSONObject()
            val req = JSONObject().put("jsonrpc", "2.0").put("id", 2)
                .put("method", "tools/call")
                .put("params", JSONObject().put("name", name)
                    .put("arguments", args))
            val response = LocalMcpProtocol.execute(req, performanceReader = { actual, data ->
                count++
                assertEquals(name, actual)
                if (needsPackage) assertEquals(pkg, data.getString("package_name"))
                JSONObject().put("status", "ok")
            })!!
            assertFalse(response.has("error"))
            assertEquals(1, count)
        }
        for (name in names.filter { it != "get_thermal_state" }) {
            val req = JSONObject().put("jsonrpc", "2.0").put("id", 3)
                .put("method", "tools/call")
                .put("params", JSONObject().put("name", name).put("arguments",
                    JSONObject().put("package_name", "com.example.player;id")))
            assertEquals(-32602, LocalMcpProtocol.execute(req)!!
                .getJSONObject("error").getInt("code"))
        }
    }
}
