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
        assertEquals(6, list.length())
        for (i in 0 until list.length()) {
            val s = list.getJSONObject(i).getJSONArray("securitySchemes").getJSONObject(0)
            assertEquals("oauth2", s.getString("type"))
            assertEquals("logcat:read", s.getJSONArray("scopes").getString(0))
        }
        val names = (0 until list.length()).map { list.getJSONObject(it).getString("name") }
        assertTrue("get_crashes" in names)
        assertFalse(names.any { "shell" in it || "exec" in it || "grant" in it })
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
