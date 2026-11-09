package dev.n2k02.mcplogger.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppRuntimeDiagnosticsTest {
    private val name = "com.example.target"

    @Test fun extractsOnlyExactAppProcessesAndSanitizedPermissionStates() {
        val ps = """
            PID UID NAME
            1234 u0_a123 com.example.target
            1235 u0_a123 com.example.target:worker
            1236 u0_a124 com.example.target2
            1237 u0_a125 com.example.other
            1238 u0_a126 sh
        """.trimIndent()
        val memory = "App Summary\nTOTAL PSS: 12500\nPrivate Dirty: 400"
        val packageDump = """
            install permissions:
              android.permission.INTERNET: granted=true
              android.permission.CAMERA: granted=false
              com.example.private.permission: granted=true
            token: should-not-leak
        """.trimIndent()
        val result = AppRuntimeDiagnostics.summarize(name, ps, memory, packageDump)
        assertEquals("running", result.getString("process_status"))
        assertEquals(2, result.getInt("process_count"))
        assertEquals(12500L, result.getLong("total_pss_kib"))
        assertEquals(2, result.getJSONArray("permissions").length())
        assertEquals(1, result.getInt("denied_permissions_in_dump"))
        val out = result.toString()
        assertFalse(out.contains("should-not-leak"))
        assertFalse(out.contains("com.example.other"))
        assertFalse(out.contains("com.example.target2"))
    }

    @Test fun processListTruncationDoesNotUnderreportMatches() {
        val ps = (1..40).joinToString("\\n") { index ->
            "${1000 + index} u0_a123 com.example.target:worker$index"
        }
        val result = AppRuntimeDiagnostics.summarize(name, ps, "", "")
        assertEquals(40, result.getInt("process_count"))
        assertEquals(30, result.getInt("returned_process_count"))
        assertTrue(result.getBoolean("processes_truncated"))
    }

    @Test fun missingFieldsAreUnavailableRatherThanZero() {
        val result = AppRuntimeDiagnostics.summarize(name, "", "Permission denied", "")
        assertEquals("not_visible_or_stopped", result.getString("process_status"))
        assertEquals("unavailable", result.getString("memory_status"))
        assertTrue(result.isNull("total_pss_kib"))
        assertEquals("unavailable_or_not_listed", result.getString("permissions_status"))
    }

    @Test fun numericUidStateIsAvailableWhenReadableAndSafeWhenNot() {
        val value = AppRuntimeDiagnostics.parseUidState("10123", "200 TOP")
        assertEquals("ok", value.getString("status"))
        assertEquals(200, value.getInt("state_code"))
        val unknown = AppRuntimeDiagnostics.parseUidState("10123", "Error: unavailable")
        assertEquals("unavailable", unknown.getString("status"))
        assertTrue(unknown.isNull("state_code"))
        assertFalse(value.toString().contains("TOP"))
        assertThrows(IllegalArgumentException::class.java) {
            AppRuntimeDiagnostics.parseUidState("10123;id", "200")
        }
    }

    @Test fun arbitraryShellSyntaxCannotBeInjected() {
        for (name in listOf("", "app", "com.example;id", "com.example/../foo",
                "com.example && rm", "com.example\nwhoami")) {
            assertThrows(IllegalArgumentException::class.java) {
                AppRuntimeDiagnostics.validate(name)
            }
        }
    }

    @Test fun mcpOnlyInvokesReaderForExplicitRuntimeTool() {
        val tools = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":1}"""))!!
            .getJSONObject("result").getJSONArray("tools")
        assertEquals(21, tools.length())
        val item = (0 until tools.length()).map { tools.getJSONObject(it) }
            .single { it.getString("name") == "get_app_runtime" }
        val schema = item.getJSONObject("inputSchema")
        assertEquals("package_name", schema.getJSONArray("required").getString(0))

        val call = JSONObject().put("jsonrpc", "2.0").put("method", "tools/call")
            .put("id", 2).put("params", JSONObject().put("name", "get_app_runtime")
                .put("arguments", JSONObject().put("package_name", name)))
        var calls = 0
        val response = LocalMcpProtocol.execute(call, performanceReader = { tool, args ->
            calls++
            assertEquals("get_app_runtime", tool)
            assertEquals(name, args.getString("package_name"))
            JSONObject().put("status", "ok")
        })!!
        assertFalse(response.has("error"))
        assertEquals(1, calls)
    }
}
