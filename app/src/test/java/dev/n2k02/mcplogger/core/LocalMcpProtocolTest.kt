package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class LocalMcpProtocolTest {
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
        assertEquals(14, payload.getInt("mcp_tool_count"))
        val toolNames = payload.getJSONArray("mcp_tool_names")
        val listed = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":2}"""),
            performanceReader = reader)!!.getJSONObject("result").getJSONArray("tools")
        assertEquals(listed.length(), toolNames.length())
        assertEquals((0 until listed.length()).map {
            listed.getJSONObject(it).getString("name")
        }, (0 until toolNames.length()).map { toolNames.getString(it) })
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
        assertEquals(14, list.length())
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

    @Test fun auditHistoryHiddenAndRejectedWhenPermissionDisabled() {
        val list = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":7}"""))!!
            .getJSONObject("result").getJSONArray("tools")
        assertEquals(14, list.length())
        assertFalse((0 until list.length()).any {
            list.getJSONObject(it).getString("name") == "get_mcp_audits"
        })
        val denied = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/call","id":8,
                 "params":{"name":"get_mcp_audits","arguments":{"limit":25}}}"""))!!
        assertEquals(-32602, denied.getJSONObject("error").getInt("code"))
    }

    @Test fun enabledAuditHistoryHasCorrectToolMetadataAndBoundedPaging() {
        val entries = (1..60).map { sample("id-" + it, it.toLong()) }
        val reader = { entries }
        val tools = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":1}"""), reader)!!
            .getJSONObject("result").getJSONArray("tools")
        assertEquals(15, tools.length())
        val audit = (0 until tools.length()).map { tools.getJSONObject(it) }
            .single { it.getString("name") == "get_mcp_audits" }
        assertTrue(audit.getJSONObject("inputSchema").getJSONObject("properties")
            .has("include_payloads"))
        val response = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/call","id":2,
                 "params":{"name":"get_mcp_audits","arguments":{"limit":500}}}"""),
            reader)!!.getJSONObject("result")
        val content = JSONObject(response.getJSONArray("content").getJSONObject(0).getString("text"))
        val first = content.getJSONArray("entries")
        assertEquals(25, first.length())
        assertEquals("id-60", first.getJSONObject(0).getString("id"))
        assertEquals("id-36", first.getJSONObject(24).getString("id"))
        assertFalse(first.getJSONObject(0).has("request"))
        assertFalse(first.getJSONObject(0).has("response"))
        assertEquals("id-36", content.getString("next_before_id"))
        val next = McpAuditPolicy.agentPage(entries, 10, "id-36")
        assertEquals("id-35", next.getJSONArray("entries").getJSONObject(0).getString("id"))
        assertEquals("id-26", next.getString("next_before_id"))
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

    @Test fun latestMcpLogRequestUpdatesUiFiltersAndExactReturnedSubset() {
        MonitorState.packageUids.value = mapOf("com.example" to "10001")
        val events = listOf(
            LogEvent(1, 600_000L, "10001", "101", "E", "Crash", "fatal event", ""),
            LogEvent(2, 600_000L, "10001", "101", "W", "App", "warning event", ""),
            LogEvent(3, 600_000L, "10002", "102", "E", "Crash", "fatal elsewhere", ""),
        )
        try {
            val req = JSONObject("""{"jsonrpc":"2.0","method":"tools/call","id":10,
                "params":{"name":"get_live_logs","arguments":{
                    "package_name":"com.example","level":"E","query":"fatal","limit":1}}}""")
            val reply = LocalMcpProtocol.execute(req, logsReader = { events })!!
            assertFalse(reply.has("error"))
            val recent = MonitorState.recentLogRequest.value!!
            assertEquals(LogReadOrigin.MCP, recent.origin)
            assertEquals("get_live_logs", recent.tool)
            assertEquals("com.example", MonitorState.logFilter.value.packageName)
            assertEquals(setOf("E"), MonitorState.logFilter.value.selectedLevels)
            assertEquals("fatal", MonitorState.logFilter.value.search)
            assertEquals(setOf(1L), recent.entryIds)
            assertTrue(recent.isMcpSelection(MonitorState.logFilter.value))
            assertFalse(recent.isMcpSelection(MonitorState.logFilter.value.copy(search = "")))
            assertEquals(1, LogViewPolicy.select(events,
                MonitorState.logFilter.value, MonitorState.packageUids.value, 600_000L).size)
        } finally {
            MonitorState.recentLogRequest.value = null
            MonitorState.logFilter.value = LogViewFilter()
            MonitorState.packageUids.value = emptyMap()
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
