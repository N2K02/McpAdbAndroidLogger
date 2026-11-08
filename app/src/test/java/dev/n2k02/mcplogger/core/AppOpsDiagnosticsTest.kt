package dev.n2k02.mcplogger.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppOpsDiagnosticsTest {
    @Test fun parsesAllowedOperationModesWithoutTimestampsOrPrivateDetails() {
        val raw = """
            CAMERA: allow; time=+1h30m, rejectTime=+5m
            RECORD_AUDIO: ignore
            ACCESS_FINE_LOCATION: foreground
            OTHER_SECRET: allow
            proxyPackage: com.private.app
            POST_NOTIFICATION: default
        """.trimIndent()
        val result = AppOpsDiagnostics.parse("com.example.app", raw)
        assertEquals("ok", result.getString("status"))
        assertEquals(4, result.getInt("operation_count"))
        val text = result.toString()
        assertTrue(text.contains("RECORD_AUDIO"))
        assertTrue(text.contains("foreground"))
        assertFalse(text.contains("OTHER_SECRET"))
        assertFalse(text.contains("time="))
        assertFalse(text.contains("com.private.app"))
    }

    @Test fun emptyOrUnsupportedOutputDoesNotMeanAllowed() {
        val result = AppOpsDiagnostics.parse("com.example.app",
            "Error: operation not permitted")
        assertEquals("unavailable_or_no_explicit_modes", result.getString("status"))
        assertEquals(0, result.getJSONArray("operations").length())
    }

    @Test fun mcpAppOpsReaderIsExplicitAndRequiresExactPackage() {
        val reply = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/call","id":5,
               "params":{"name":"get_app_ops","arguments":{"package_name":"com.example.app"}}}"""),
            performanceReader = { name, args ->
                assertEquals("get_app_ops", name)
                assertEquals("com.example.app", args.getString("package_name"))
                JSONObject().put("status", "ok")
            })!!
        assertFalse(reply.has("error"))
        val denied = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/call","id":6,
               "params":{"name":"get_app_ops","arguments":{"package_name":"com.example;id"}}}"""))!!
        assertEquals(-32602, denied.getJSONObject("error").getInt("code"))
    }
}
