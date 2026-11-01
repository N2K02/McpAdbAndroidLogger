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
    @Test fun listToolsContainsOnlyReadOnlyOperations() {
        val reply = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":2}"""))!!
        val list = reply.getJSONObject("result").getJSONArray("tools")
        assertEquals(9, list.length())
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
        assertEquals(9, list.length())
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
        assertEquals(10, tools.length())
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
