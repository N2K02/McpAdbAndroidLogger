package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class LocalMcpProtocolTest {
    @Test fun mcpHomeResetClearsOnlyCountersWhileRequestRemainsActive() {
        val originalReads = MonitorState.mcpReadCount.value
        val originalLastRead = MonitorState.mcpLastReadAt.value
        val originalStats = MonitorState.captureStats.value
        val originalLogs = MonitorState.logs.value
        val originalAdb = MonitorState.adb.value
        val originalTunnel = MonitorState.tunnel.value
        MonitorState.beginMcpTool("mcp-reset-test")
        try {
            MonitorState.registerMcpRead()
            assertTrue(MonitorState.mcpReadCount.value > 0)
            MonitorState.resetMcpHomeStatistics()
            assertEquals(0L, MonitorState.mcpReadCount.value)
            assertEquals(0L, MonitorState.mcpLastReadAt.value)
            assertTrue("mcp-reset-test" in MonitorState.mcpToolsInFlight.value)
            assertEquals(originalStats, MonitorState.captureStats.value)
            assertEquals(originalLogs, MonitorState.logs.value)
            assertEquals(originalAdb, MonitorState.adb.value)
            assertEquals(originalTunnel, MonitorState.tunnel.value)
            MonitorState.registerMcpRead()
            assertEquals(1L, MonitorState.mcpReadCount.value)
            assertTrue(MonitorState.mcpLastReadAt.value > 0)
        } finally {
            MonitorState.endMcpTool("mcp-reset-test")
            MonitorState.mcpReadCount.value = originalReads
            MonitorState.mcpLastReadAt.value = originalLastRead
        }
    }

    @Test fun initializeReturnsCapabilitiesAndVersion() {
        val reply = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"initialize","id":1}"""))!!
        assertEquals(1, reply.getInt("id"))
        assertEquals("2025-11-25", reply.getJSONObject("result").getString("protocolVersion"))
    }
    @Test fun existingDeviceStatusReportsTheLiveCatalogWithoutRunningDiagnostics() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val reader: (String, JSONObject) -> JSONObject = { _, _ ->
            calls.incrementAndGet()
            JSONObject().put("status", "unexpected")
        }
        val reply = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/call","id":1,
               "params":{"name":"get_device_status","arguments":{"limit":20}}}"""),
            performanceReader = reader)!!
        assertFalse(reply.has("error"))
        val payload = JSONObject(reply.getJSONObject("result")
            .getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(LocalMcpProtocol.CATALOG_REVISION,
            payload.getString("mcp_catalog_revision"))
        assertEquals(25, payload.getInt("mcp_tool_count"))
        val toolNames = payload.getJSONArray("mcp_tool_names")
        val listed = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":2}"""),
            performanceReader = reader)!!.getJSONObject("result").getJSONArray("tools")
        assertEquals(listed.length(), toolNames.length())
        assertEquals((0 until listed.length()).map {
            listed.getJSONObject(it).getString("name")
        }, (0 until toolNames.length()).map { toolNames.getString(it) })
        assertEquals(MonitorState.active.value,
            payload.getBoolean("manual_logcat_reading"))
        assertEquals(MonitorState.logs.value.size,
            payload.getInt("manual_buffered_logs"))
        assertTrue(payload.getJSONArray("mcp_tools_active").toString()
            .contains("get_device_status"))
        assertEquals(2000, payload.getInt("logcat_snapshot_max_lines"))
        assertTrue(payload.getString("package_log_filter").contains("exact UID"))
        assertTrue(payload.getJSONArray("diagnostic_coverage_notes").length() >= 4)
        assertTrue(payload.getBoolean("on_demand_health_available"))
        assertEquals(MonitorState.adb.value.startsWith("UID 2000 verified"),
            payload.getBoolean("on_demand_health_ready"))
        assertEquals(0, calls.get())
        assertFalse(payload.toString().contains("access_key"))
        assertFalse(payload.toString().contains("token"))
    }

    @Test fun listToolsContainsOnlyReadOnlyOperations() {
        val reply = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":2}"""))!!
        val list = reply.getJSONObject("result").getJSONArray("tools")
        assertEquals(25, list.length())
        for (i in 0 until list.length()) {
            val s = list.getJSONObject(i).getJSONArray("securitySchemes").getJSONObject(0)
            assertEquals("oauth2", s.getString("type"))
            assertEquals("logcat:read", s.getJSONArray("scopes").getString(0))
        }
        val names = (0 until list.length()).map { list.getJSONObject(it).getString("name") }
        assertTrue("get_crashes" in names)
        assertFalse(names.any { "shell" in it || "exec" in it || "grant" in it })
    }
    @Test fun logToolsAdvertisePackageFilterAndPreserveOtherToolSchemas() {
        val response = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":1}"""))!!
        val tools = response.getJSONObject("result").getJSONArray("tools")
        val schemas = (0 until tools.length()).associate { index ->
            val entry = tools.getJSONObject(index)
            entry.getString("name") to entry.getJSONObject("inputSchema")
        }
        for (tool in listOf("get_live_logs", "search_logs", "get_crashes", "get_anrs")) {
            val schema = schemas.getValue(tool)
            assertTrue("$tool missing package_name",
                schema.getJSONObject("properties").has("package_name"))
            assertEquals("string", schema.getJSONObject("properties")
                .getJSONObject("package_name").getString("type"))
            assertFalse(schema.getJSONObject("properties").getJSONObject("package_name")
                .getString("description").contains("message text"))
        }
        assertTrue(schemas.getValue("get_live_logs").getJSONObject("properties")
            .getJSONObject("format").getString("description")
            .contains("uid+epoch"))
        assertFalse(schemas.getValue("get_device_status").getJSONObject("properties")
            .has("package_name"))
        assertFalse(schemas.getValue("list_installed_apps").getJSONObject("properties")
            .has("package_name"))
        assertFalse(schemas.getValue("search_logs").optJSONArray("required") == null)
    }

    private fun sample(id: String, time: Long, req: String = """{"token":"secret-value"}""") =
        McpAuditEntry(id, time, "client-id", "ChatGPT", "chatgpt.com",
            "MCP", "tools/call", "OK", 2, req, """{"api_key":"sensitive"}""",
            false, false, "POST", "/mcp", 200, "")

    @Test fun remoteAuditHistoryNeverAvailableEvenIfReaderProvided() {
        val reader = { listOf(sample("id-1", 123L)) }
        val list = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":7}"""), reader)!!
            .getJSONObject("result").getJSONArray("tools")
        assertEquals(25, list.length())
        assertFalse((0 until list.length()).any {
            list.getJSONObject(it).getString("name") == "get_mcp_audits"
        })
        val denied = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/call","id":8,
                 "params":{"name":"get_mcp_audits","arguments":{"limit":25}}}"""), reader)!!
        assertEquals(-32602, denied.getJSONObject("error").getInt("code"))
    }

    @Test fun agentAuditPayloadsAreOptInAndRedactedAgain() {
        val data = listOf(sample("id-1", 123L))
        val metadata = McpAuditPolicy.agentPage(data, 20)
        assertFalse(metadata.getJSONArray("entries").getJSONObject(0).has("request"))
        val included = McpAuditPolicy.agentPage(data, 20, includePayloads = true)
            .getJSONArray("entries").getJSONObject(0)
        assertTrue(included.has("request"))
        assertTrue(included.has("response"))
        assertFalse(included.toString().contains("secret-value"))
        assertFalse(included.toString().contains("sensitive"))
        assertTrue(included.getString("request").contains("[REDACTED]"))
        assertThrows(IllegalArgumentException::class.java) {
            McpAuditPolicy.agentPage(data, 20, "id-unknown")
        }
    }

    @Test fun remoteMcpLogRequestsNeverChangeLocalFiltersOrManualSelection() {
        val originalFilter = MonitorState.logFilter.value
        val originalSelectedPackage = MonitorState.selectedPackage.value
        val originalRecentRequest = MonitorState.recentLogRequest.value
        val originalUidMap = MonitorState.packageUids.value
        val localFilter = LogViewFilter(packageName = "com.other.app",
            search = "warning", selectedLevels = setOf("W"))
        val previousManual = RecentLogRequest(LogReadOrigin.MANUAL,
            "Manual refresh", 1234567L)
        val events = listOf(
            LogEvent(1, 600_000L, "10001", "101", "E", "Crash", "FATAL EXCEPTION", ""),
            LogEvent(2, 600_000L, "10001", "101", "W", "App", "warning event", ""),
            LogEvent(3, 600_000L, "10002", "102", "E", "Crash", "fatal elsewhere", ""),
            LogEvent(4, 600_000L, "10001", "101", "W", "ActivityManager",
                "ANR in com.example", ""),
            LogEvent(5, 600_000L, "10002", "102", "W", "App",
                "warning for local UI", ""),
        )
        MonitorState.logFilter.value = localFilter
        MonitorState.selectedPackage.value = localFilter.packageName
        MonitorState.recentLogRequest.value = previousManual
        MonitorState.packageUids.value = mapOf("com.example" to "10001",
            "com.other.app" to "10002")
        try {
            val requests = listOf(
                "get_live_logs" to """{"package_name":"com.example","level":"E",
                    "query":"FATAL","limit":1}""",
                "search_logs" to """{"package_name":"com.example",
                    "query":"warning","limit":1}""",
                "get_crashes" to """{"package_name":"com.example","limit":1}""",
                "get_anrs" to """{"package_name":"com.example","limit":1}""",
            )
            val expectedIds = listOf(1L, 2L, 1L, 4L)
            requests.forEachIndexed { index, (tool, args) ->
                val request = JSONObject().put("jsonrpc", "2.0").put("id", index + 1)
                    .put("method", "tools/call")
                    .put("params", JSONObject().put("name", tool)
                        .put("arguments", JSONObject(args)))
                val reply = LocalMcpProtocol.execute(request, logsReader = { events })!!
                assertFalse("$tool error: $reply", reply.has("error"))
                val returned = org.json.JSONArray(reply.getJSONObject("result")
                    .getJSONArray("content").getJSONObject(0).getString("text"))
                assertEquals("$tool response size", 1, returned.length())
                assertEquals("$tool result", expectedIds[index],
                    returned.getJSONObject(0).getLong("cursor"))

                // Remote arguments must affect only the response, never device UI.
                assertEquals("$tool changed Logs filter", localFilter,
                    MonitorState.logFilter.value)
                assertEquals("$tool changed selected app", localFilter.packageName,
                    MonitorState.selectedPackage.value)
                assertEquals("$tool replaced last manual request", previousManual,
                    MonitorState.recentLogRequest.value)
                assertEquals("$tool changed UI visible subset", listOf(5L),
                    LogViewPolicy.select(events, MonitorState.logFilter.value,
                        MonitorState.packageUids.value, 600_000L).map { it.id })
            }
        } finally {
            MonitorState.logFilter.value = originalFilter
            MonitorState.selectedPackage.value = originalSelectedPackage
            MonitorState.recentLogRequest.value = originalRecentRequest
            MonitorState.packageUids.value = originalUidMap
        }
    }

    @Test fun homeMcpReadBadgesFollowToolLifetimeEvenOnFailure() {
        val logRequest = JSONObject("""{"jsonrpc":"2.0","method":"tools/call","id":11,
            "params":{"name":"get_live_logs"}}""")
        val cpuRequest = JSONObject("""{"jsonrpc":"2.0","method":"tools/call","id":12,
            "params":{"name":"get_cpu_usage"}}""")
        MonitorState.clearMcpTools()
        try {
            assertTrue(MonitorState.mcpToolsInFlight.value.isEmpty())
            val logResult = LocalMcpProtocol.execute(logRequest, logsReader = {
                assertTrue("get_live_logs" in MonitorState.mcpToolsInFlight.value)
                emptyList()
            })!!
            assertFalse(logResult.has("error"))
            assertTrue(MonitorState.mcpToolsInFlight.value.isEmpty())

            val cpuResult = LocalMcpProtocol.execute(cpuRequest, performanceReader = { _, _ ->
                assertTrue("get_cpu_usage" in MonitorState.mcpToolsInFlight.value)
                JSONObject().put("status", "ok")
            })!!
            assertFalse(cpuResult.has("error"))
            assertTrue(MonitorState.mcpToolsInFlight.value.isEmpty())

            val failed = LocalMcpProtocol.execute(logRequest, logsReader = {
                assertTrue("get_live_logs" in MonitorState.mcpToolsInFlight.value)
                throw IllegalStateException("test read failure")
            })!!
            assertTrue(failed.has("error"))
            assertTrue(MonitorState.mcpToolsInFlight.value.isEmpty())

            MonitorState.beginMcpTool("get_live_logs")
            MonitorState.beginMcpTool("get_live_logs")
            MonitorState.endMcpTool("get_live_logs")
            assertEquals(setOf("get_live_logs"), MonitorState.mcpToolsInFlight.value)
            MonitorState.endMcpTool("get_live_logs")
            assertTrue(MonitorState.mcpToolsInFlight.value.isEmpty())
        } finally {
            MonitorState.clearMcpTools()
        }
    }

    @Test fun rejectsExecutionAndUnknownMethods() {
        val unknown = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"run_command","id":3}"""))!!
        assertEquals(-32601, unknown.getJSONObject("error").getInt("code"))
        val tool = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/call","id":4,"params":{"name":"shell"}}"""))!!
        assertEquals(-32602, tool.getJSONObject("error").getInt("code"))
    }
    @Test fun notificationHasNoResponse() {
        assertNull(LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""")))
    }
}
