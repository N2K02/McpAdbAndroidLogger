package dev.n2k02.mcplogger.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveAppDiagnosticsTest {
    private val req = LiveAppDiagnostics.Request("com.example.app", 3, 50, "audio")
    private fun event(id: Long, uid: String, timestamp: Long,
        priority: String = "W") = LogEvent(id, timestamp, uid,
        "123", priority, "AudioFlinger", "buffer underrun", "")

    @Test fun exactlyOneBoundedReadAndVerifiedUidFilter() = runBlocking {
        var clock = 1_800_000_000_000L
        var reads = 0
        var waited = 0L
        val result = LiveAppDiagnostics.observe(req,
            uidLookup = { assertEquals("com.example.app", it); "10123" },
            waitWindow = { waited = it; clock += it },
            read = { start, timeout, profile ->
                reads++
                assertEquals(1_800_000_000_000L, start)
                assertEquals(2000L, timeout)
                assertEquals("audio", profile)
                listOf(event(1, "10123", start + 1000),
                    event(2, "10124", start + 1000),
                    event(3, "10123", start - 100),
                    event(4, "10123", start + 2000, "E"))
            },
            clock = { clock })
        assertEquals(3000L, waited)
        assertEquals(1, reads)
        assertEquals(2, result.getInt("log_count"))
        assertEquals(1, result.getInt("errors"))
        assertEquals(1, result.getInt("warnings"))
        assertEquals("single_on_demand_window", result.getString("mode"))
        assertEquals(2, result.getJSONArray("events").length())
    }

    @Test fun liveMonitoringFailsClosedForUnknownOrInvalidUid() = runBlocking {
        var waited = false
        for (uid in listOf(null, "2000;id", "not-a-uid")) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    LiveAppDiagnostics.observe(req,
                        uidLookup = { uid },
                        waitWindow = { waited = true },
                        read = { _, _, _ -> emptyList() })
                }
            }
        }
        assertFalse(waited)
    }

    @Test fun rejectsOversizeDurationsAndUnsafePackages() {
        for (json in listOf(
            """{"package_name":"com.example.app","duration_seconds":8}""",
            """{"package_name":"com.example.app","duration_seconds":0}""",
            """{"package_name":"com.example.app","duration_seconds":100}""",
            """{"package_name":"com.example.app","duration_seconds":"7"}""",
            """{"package_name":"com.example.app;id"}""",
            """{"package_name":"com.example.app","limit":10000}""",
            """{"package_name":"com.example.app","profile":"bogus"}"""
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                LiveAppDiagnostics.parse(JSONObject(json))
            }
        }
        assertEquals(3, LiveAppDiagnostics.parse(JSONObject(
            """{"package_name":"com.example.app"}""")).durationSeconds)
    }

    @Test fun liveMcpToolIsOnDemandAndDoesNotExposeRemoteControl() {
        val request = JSONObject().put("jsonrpc", "2.0").put("id", 4)
            .put("method", "tools/call")
            .put("params", JSONObject().put("name", "monitor_app_live")
                .put("arguments", JSONObject().put("package_name", "com.example.app")
                    .put("duration_seconds", 2)))
        var calls = 0
        val reply = LocalMcpProtocol.execute(request, liveReader = { args ->
            calls++
            assertEquals("com.example.app", args.getString("package_name"))
            assertEquals(2, args.getInt("duration_seconds"))
            JSONObject().put("mode", "single_on_demand_window")
        })!!
        assertFalse(reply.has("error"))
        assertEquals(1, calls)
        val denied = LocalMcpProtocol.execute(request)!!
        assertEquals(-32603, denied.getJSONObject("error").getInt("code"))
    }
}
