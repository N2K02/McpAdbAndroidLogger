package dev.n2k02.mcplogger.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream

class DiagnosticFeaturesTest {
    private fun call(name: String, args: JSONObject = JSONObject()) = JSONObject()
        .put("jsonrpc", "2.0").put("id", 10).put("method", "tools/call")
        .put("params", JSONObject().put("name", name).put("arguments", args))

    private fun payload(response: JSONObject): String =
        response.getJSONObject("result").getJSONArray("content")
            .getJSONObject(0).getString("text")

    private fun event(id: Long, tag: String = "AudioFlinger",
                      level: String = "W") =
        LogEvent(id, 1_700_000_000_000, "2000", "111", level,
            tag, "audio buffer underrun", "")

    @Test fun localMcpCatalogPublishesCorrelationAndPresetFilters() {
        val response = LocalMcpProtocol.execute(JSONObject()
            .put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list"))!!
        val tools = response.getJSONObject("result").getJSONArray("tools")
        val entries = (0 until tools.length()).map { tools.getJSONObject(it) }
        val correlation = entries.single {
            it.getString("name") == "get_diagnostic_correlation"
        }
        assertTrue(correlation.getJSONObject("inputSchema")
            .getJSONObject("properties").has("profile"))
        val presets = LocalMcpProtocol.execute(call("list_logcat_profiles"))!!
        assertTrue(JSONObject(payload(presets)).has("audio"))
    }

    @Test fun captureReaderGetsValidatedOptionsWithoutMutatingOnDeviceFilter() {
        val before = MonitorState.logFilter.value
        var captured = 0
        val response = LocalMcpProtocol.execute(call("get_live_logs",
            JSONObject().put("profile", "audio").put("limit", 10)),
            captureReader = { options ->
                captured++
                assertEquals("snapshot", options.mode)
                assertTrue(options.tags.any { it.first == "AudioFlinger" })
                listOf(event(1), event(2, "Other", "E"))
            })!!
        assertFalse(response.has("error"))
        val logs = JSONArray(payload(response))
        assertEquals(1, logs.length())
        assertEquals("AudioFlinger", logs.getJSONObject(0).getString("tag"))
        assertEquals(1, captured)
        assertEquals(before, MonitorState.logFilter.value)
    }

    @Test fun correlationCombinesBoundedSnapshotsAndRedactedLogs() {
        val calls = mutableListOf<String>()
        val response = LocalMcpProtocol.execute(call("get_diagnostic_correlation",
            JSONObject().put("profile", "audio").put("limit", 1)),
            captureReader = { listOf(event(1), event(2)) },
            performanceReader = { tool, _ ->
                calls.add(tool)
                JSONObject().put("status", "ok")
            })!!
        assertFalse(response.has("error"))
        val result = JSONObject(payload(response))
        assertEquals(1, result.getJSONArray("logcat").length())
        assertTrue(result.getLong("window_end_ms") >= result.getLong("window_start_ms"))
        assertEquals(listOf("get_cpu_usage", "get_memory_pressure",
            "get_battery_diagnostics"), calls)
        assertTrue(result.getString("causality").contains("not"))
    }

    @Test fun remoteSessionAndProfileMutationsAreNotExposed() {
        for (name in listOf("start_diagnostic_session", "save_logcat_profile",
                "delete_logcat_profile", "exec_shell")) {
            val response = LocalMcpProtocol.execute(call(name))!!
            assertEquals(-32602, response.getJSONObject("error").getInt("code"))
        }
        val response = LocalMcpProtocol.execute(call("get_live_logs",
            JSONObject().put("mode", "stream")), logsReader = { emptyList() })!!
        assertEquals(-32602, response.getJSONObject("error").getInt("code"))
    }

    @Test fun redactedJsonAndZipAreBounded() {
        val entries = (1..2100).map { event(it.toLong()) }
        val json = JSONObject(DiagnosticLogExport.json(entries))
        assertEquals(2000, json.getJSONArray("events").length())
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(DiagnosticLogExport.zip(entries))).use { zip ->
            while (true) {
                val next = zip.nextEntry ?: break
                names.add(next.name)
                assertFalse(next.name.startsWith("/"))
                zip.closeEntry()
            }
        }
        assertEquals(listOf("logcat.json", "logcat.txt"), names)
    }
}
