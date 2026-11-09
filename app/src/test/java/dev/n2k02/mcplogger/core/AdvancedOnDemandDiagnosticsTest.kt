package dev.n2k02.mcplogger.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdvancedOnDemandDiagnosticsTest {
    private fun event(id: Long, uid: String, tag: String, level: String,
                      message: String) = LogEvent(id, 1794000000000L + id,
        uid, "123", level, tag, message, "")

    @Test fun triageGroupsValidatedUidEventsWithoutRawStackTraces() {
        val events = listOf(
            event(1, "10123", "AndroidRuntime", "E",
                "FATAL EXCEPTION: java.lang.IllegalStateException secret token"),
            event(2, "10123", "AndroidRuntime", "E",
                "java.lang.IllegalStateException stack at /data/user/private"),
            event(3, "10123", "ActivityManager", "E", "ANR in com.example.app"),
            event(4, "1000", "ActivityManager", "E", "ANR in com.example.app"),
            event(5, "10124", "AndroidRuntime", "E", "FATAL EXCEPTION"))
        val r = CrashTriageDiagnostics.summarize(events, "com.example.app",
            mapOf("com.example.app" to "10123"))
        assertEquals("events_detected", r.getString("status"))
        assertEquals(3, r.getInt("event_count"))
        val body = r.toString()
        assertFalse(body.contains("secret token"))
        assertFalse(body.contains("/data/user/private"))
        assertFalse(body.contains("10124"))
        assertFalse(body.contains("\"uid\":\"1000\""))
        assertTrue(r.getJSONArray("signatures").length() > 0)
    }

    @Test fun triageFailsClosedForUnverifiedPackageAndEmptyBuffers() {
        val events = listOf(event(1, "1000", "ActivityManager", "E",
            "ANR in com.example.app"))
        val missing = CrashTriageDiagnostics.summarize(events, "com.example.app",
            emptyMap())
        assertEquals("package_uid_unavailable", missing.getString("status"))
        assertEquals(0, missing.getInt("event_count"))
        val none = CrashTriageDiagnostics.summarize(emptyList(), "", emptyMap())
        assertEquals("insufficient_evidence", none.getString("status"))
        assertThrows(IllegalArgumentException::class.java) {
            CrashTriageDiagnostics.summarize(events, "com.example;id", emptyMap())
        }
    }

    @Test fun capabilityListDoesNotAssumeDumpsysPrivileges() {
        val r = OnDemandSystemDiagnostics.parseCapabilities("""
            Currently running services:
              activity
              audio
              jobscheduler
              thermalservice
        """.trimIndent())
        assertEquals("ok", r.getString("status"))
        assertEquals("service_listed_readability_unverified",
            r.getJSONObject("services").getString("audio_state"))
        assertEquals("not_listed",
            r.getJSONObject("services").getString("surfaceflinger"))
        assertFalse(r.toString().contains("Currently running services"))
        val unavailable = OnDemandSystemDiagnostics.parseCapabilities(
            "Permission denied")
        assertEquals("unavailable", unavailable.getString("status"))
    }

    @Test fun structuredAudioAndJobsAreHintsNotFalseVerdicts() {
        val audio = OnDemandSystemDiagnostics.parseAudio("com.example.app",
            "focus_gain for com.example.app\nrecording=true com.example.app")
        assertEquals("unknown", audio.getString("focus_state"))
        assertTrue(audio.getJSONArray("audio_evidence").toString()
            .contains("recording_started_mentioned"))
        val jobs = OnDemandSystemDiagnostics.parseJobs("com.example.app",
            "JobStatus{123 com.example.app}\nquota exhausted\n" +
            "unsatisfied connectivity constraint")
        assertEquals("unknown", jobs.getString("quota_state"))
        assertTrue(jobs.getJSONArray("restriction_evidence").toString()
            .contains("quota_mentioned"))
        assertTrue(jobs.getJSONArray("restriction_evidence").toString()
            .contains("unsatisfied_connectivity"))
    }

    @Test fun toolsAreAdvertisedAndDispatchOnlyWhenExplicitlyRequested() {
        val catalog = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":1}"""))!!
            .getJSONObject("result").getJSONArray("tools")
        assertEquals(27, catalog.length())
        val names = (0 until catalog.length()).map {
            catalog.getJSONObject(it).getString("name")
        }
        assertTrue("get_crash_triage" in names)
        assertTrue("get_device_capabilities" in names)

        var captures = 0
        val crashCall = JSONObject("""{"jsonrpc":"2.0","method":"tools/call","id":2,
            "params":{"name":"get_crash_triage","arguments":{}}}""")
        val response = LocalMcpProtocol.execute(crashCall,
            captureReader = { captures++; emptyList() })!!
        assertFalse(response.has("error"))
        assertEquals(1, captures)
        val data = JSONObject(response.getJSONObject("result")
            .getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("insufficient_evidence", data.getString("status"))

        var probes = 0
        val req = JSONObject("""{"jsonrpc":"2.0","method":"tools/call","id":3,
            "params":{"name":"get_device_capabilities","arguments":{}}}""")
        val probe = LocalMcpProtocol.execute(req,
            performanceReader = { name, _ ->
                assertEquals("get_device_capabilities", name)
                probes++
                JSONObject().put("status", "ok")
            })!!
        assertFalse(probe.has("error"))
        assertEquals(1, probes)
    }
}
