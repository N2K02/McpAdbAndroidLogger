package dev.n2k02.mcplogger.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** All 27 public MCP tools must remain invocable and return valid JSON-RPC. */
class McpAllToolsRegressionTest {
    private val app = "dev.n2k02.mcplogger"
    private val appTools = setOf("get_app_runtime", "get_app_ops",
        "get_app_services", "get_app_audio_state", "get_app_jobs")
    private val logTools = setOf("get_live_logs", "search_logs", "get_crashes",
        "get_anrs", "get_crash_triage", "get_diagnostic_correlation")
    private val performanceTools = setOf("get_cpu_usage", "get_frame_timing",
        "get_performance_snapshot", "get_battery_diagnostics",
        "get_memory_pressure", "get_wakelock_usage", "get_screen_off_baseline",
        "get_screen_off_report", "get_surfaceflinger_layers",
        "get_surfaceflinger_activity", "get_surfaceflinger_perfetto",
        "get_app_runtime", "get_app_ops", "get_app_services",
        "get_app_audio_state", "get_app_jobs", "get_thermal_state",
        "get_device_capabilities")

    private fun request(tool: String): JSONObject {
        val args = JSONObject()
        if (tool in appTools || tool == "get_frame_timing")
            args.put("package_name", app)
        if (tool == "search_logs") args.put("query", "AndroidRuntime")
        if (tool == "get_surfaceflinger_activity" ||
            tool == "get_surfaceflinger_perfetto") args.put("duration_seconds", 3)
        return JSONObject().put("jsonrpc", "2.0").put("id", 1)
            .put("method", "tools/call")
            .put("params", JSONObject().put("name", tool).put("arguments", args))
    }

    @Test fun allAdvertisedToolsHaveDispatchCoverage() {
        val catalog = LocalMcpProtocol.builtInToolNames()
        assertEquals(27, catalog.size)
        assertEquals(27, catalog.distinct().size)
        catalog.forEach { tool ->
            val result = LocalMcpProtocol.execute(request(tool),
                logsReader = { emptyList() },
                appsReader = { listOf(app) },
                captureReader = { emptyList() },
                performanceReader = { name, _ ->
                    assertTrue("Unexpected diagnostic dispatcher: $name", name in performanceTools)
                    JSONObject().put("status", "ok")
                })!!
            assertFalse("Unexpected JSON-RPC error in $tool: $result", result.has("error"))
            val body = result.getJSONObject("result")
            assertFalse("Unexpected tool error: $tool", body.getBoolean("isError"))
            assertEquals("text", body.getJSONArray("content")
                .getJSONObject(0).getString("type"))
        }
    }

    @Test fun diagnosticFailureReturnsStructuredRetryableToolError() {
        val response = LocalMcpProtocol.execute(request("get_thermal_state"),
            performanceReader = { _, _ -> throw IllegalStateException("private shell stderr") })!!
        assertFalse(response.has("error"))
        val result = response.getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
        val body = JSONObject(result.getJSONArray("content")
            .getJSONObject(0).getString("text"))
        assertEquals("unavailable", body.getString("status"))
        assertEquals("diagnostic_failed", body.getString("reason"))
        assertTrue(body.getBoolean("retryable"))
        assertFalse(response.toString().contains("private shell stderr"))
    }

    @Test fun badPackageStillUsesInvalidArgumentsError() {
        val bad = request("get_app_runtime")
        bad.getJSONObject("params").getJSONObject("arguments")
            .put("package_name", "com.invalid;echo")
        val result = LocalMcpProtocol.execute(bad,
            performanceReader = { _, _ -> JSONObject() })!!
        assertEquals(-32602, result.getJSONObject("error").getInt("code"))
    }

    @Test fun manifestDoesNotDropOrAddToolsUnexpectedly() {
        val r = LocalMcpProtocol.execute(JSONObject()
            .put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list"))!!
        val listed = r.getJSONObject("result").getJSONArray("tools")
        val names = (0 until listed.length()).map {
            listed.getJSONObject(it).getString("name")
        }
        assertEquals(LocalMcpProtocol.builtInToolNames(), names)
    }
}
