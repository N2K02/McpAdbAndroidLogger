package dev.n2k02.mcplogger.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ShizukuTransportProfileContractTest {
    @Test fun transportToolIsPublishedWithoutIntegratedAdbDependencies() {
        val names = LocalMcpProtocol.builtInToolNames()
        assertEquals(29, names.size)
        assertEquals(1, names.count { it == "get_adb_transport_profile" })
        val listed = LocalMcpProtocol.execute(JSONObject()
            .put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list"))!!
            .getJSONObject("result").getJSONArray("tools")
        val tool = (0 until listed.length()).map { listed.getJSONObject(it) }
            .single { it.getString("name") == "get_adb_transport_profile" }
        assertEquals(0, tool.getJSONObject("inputSchema")
            .getJSONObject("properties").length())
    }

    @Test fun profileDispatchesWithoutAcceptingCallerShellArguments() {
        var called = 0
        val request = JSONObject().put("jsonrpc", "2.0").put("id", 7)
            .put("method", "tools/call")
            .put("params", JSONObject()
                .put("name", "get_adb_transport_profile")
                .put("arguments", JSONObject()))
        val answer = LocalMcpProtocol.execute(request, performanceReader = { name, args ->
            called++
            assertEquals("get_adb_transport_profile", name)
            assertEquals(0, args.optString("package_name").length)
            JSONObject().put("transport", "shizuku_binder").put("status", "ok")
        })!!
        assertFalse(answer.has("error"))
        assertEquals(1, called)
        val profile = JSONObject(answer.getJSONObject("result").getJSONArray("content")
            .getJSONObject(0).getString("text"))
        assertEquals("shizuku_binder", profile.getString("transport"))
    }

    @Test fun fixedUidProbeDoesNotUseTcpOrPairing() {
        fun source(path: String): String {
            val p = "app/src/main/java/dev/n2k02/mcplogger/" + path
            return listOf(File(p), File("../" + p)).first { it.isFile }.readText()
        }
        val transport = source("core/ShizukuTransportDiagnostics.kt")
        assertTrue(transport.contains("AdbTransport.exec(\"id -u\""))
        assertTrue(transport.contains("ShizukuHostAdapter.current()"))
        assertFalse(transport.contains("connectTcp("))
        assertFalse(transport.contains("pairingCode"))
    }
}
