package dev.n2k02.mcplogger.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpAuditPolicyTest {
    private fun entry(id: String, agent: String = "a", tool: String = "search_logs",
                      status: String = "OK", time: Long = 600000L) =
        McpAuditEntry(id, time, agent, agent, "agent.example", "tools/call", tool,
            status, 15, "request", "response", false, false)

    @Test fun redactDeepOauthSecretsAndLogcatPersonalData() {
        val input = JSONObject()
            .put("params", JSONObject()
                .put("access_token", "secret-access")
                .put("password", "hidden")
                .put("code_verifier", "pkce-key")
                .put("query", "email=test@example.com token=abc123"))
        val out = McpAuditPolicy.snapshot(input.toString(), 10000).text
        assertFalse(out.contains("secret-access"))
        assertFalse(out.contains("hidden"))
        assertFalse(out.contains("pkce-key"))
        assertFalse(out.contains("test@example.com"))
        assertFalse(out.contains("abc123"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test fun limitSnapshotsWithExplicitTruncation() {
        val input = "{\"message\":\"" + "x".repeat(50000) + "\"}"
        val result = McpAuditPolicy.snapshot(input, 120)
        assertTrue(result.truncated)
        assertTrue(result.text.contains("truncated"))
        assertTrue(result.text.length < 300)
    }

    @Test fun filterByAgentToolStatusTextAndTime() {
        val entries = listOf(
            entry("1", "claude", "search_logs", "OK", 600000),
            entry("2", "chatgpt", "get_crashes", "Error", 600000),
            entry("3", "claude", "get_crashes", "OK", 100000))
        val matches = McpAuditPolicy.select(entries, McpAuditPolicy.Filter(
            agentId = "claude", tool = "search_logs", status = "OK",
            query = "search_logs", minutes = 5), 600000)
        assertEquals(listOf("1"), matches.map { it.id })
        assertEquals(3, McpAuditPolicy.select(entries, McpAuditPolicy.Filter(), 600000).size)
    }

    @Test fun deniedClientHintIsClearlyUnverifiedAndNeverStoresRawHeaders() {
        assertEquals("ChatGPT (unverified)",
            McpAuditPolicy.clientHint("https://chatgpt.com", "custom agent"))
        assertEquals("Claude (unverified)",
            McpAuditPolicy.clientHint(null, "claude-mcp/1.0"))
        assertEquals("Browser (unverified)",
            McpAuditPolicy.clientHint(null, "Mozilla/5.0 Chrome/123"))
        assertEquals("Unknown (unverified)",
            McpAuditPolicy.clientHint("https://secret.example/path?token=x", "private-client"))
        assertFalse(McpAuditPolicy.clientHint("https://secret.example", "secret-value")
            .contains("secret-value"))
    }

    @Test fun httpMetadataSurvivesJsonAndLegacyRowsStillOpen() {
        val updated = entry("1").copy(httpMethod = "GET", endpoint = "/mcp",
            httpStatus = 401, reason = "Bearer authorization missing")
        assertEquals(updated, McpAuditEntry.fromJson(updated.toJson()))
        val old = entry("2").toJson()
        old.remove("http_method"); old.remove("endpoint")
        old.remove("http_status"); old.remove("reason")
        val decoded = McpAuditEntry.fromJson(old)
        assertEquals("", decoded.httpMethod)
        assertEquals(0, decoded.httpStatus)
    }


    @Test fun retainsOnlyTodayAndPrunesYesterdayAtLocalMidnight() {
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 2, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val midnight = cal.timeInMillis
        val all = listOf(
            entry("yesterday", time = midnight - 1),
            entry("today", time = midnight),
            entry("later", time = midnight + 1_000),
            entry("tomorrow", time = LogDayPolicy.nextMidnight(midnight)))
        val today = McpAuditPolicy.today(all, midnight + 5_000)
        assertEquals(listOf("today", "later"), today.map { it.id })
        assertEquals(emptyList<McpAuditEntry>(), McpAuditPolicy.today(
            listOf(entry("old", time = midnight - 1)), midnight))
    }

    @Test fun auditStorePrunesDiskOnStartupReadWriteAndMidnightTick() {
        val path = "app/src/main/java/dev/n2k02/mcplogger/core/McpAuditStore.kt"
        val source = listOf(java.io.File(path), java.io.File("../$path"))
            .first { it.isFile }.readText()
        assertTrue(source.contains("McpAuditPolicy.today(old, nowMs)"))
        assertTrue(source.contains("fun prune(c: Context)"))
        assertTrue(source.contains("synchronized(lock) { pruneLocked(c) }"))
        assertTrue(source.contains("pruneLocked(c) + entry"))
        assertTrue(source.contains("destination.delete()"))
        assertTrue(source.contains("MAX_READ_CHARS = 8_000_000"))
        assertTrue(source.contains("current.isEmpty() && file(c).baseFile.isFile"))
        val servicePath = "app/src/main/java/dev/n2k02/mcplogger/core/LogMonitorService.kt"
        val service = listOf(java.io.File(servicePath), java.io.File("../$servicePath"))
            .first { it.isFile }.readText()
        assertTrue(service.contains("McpAuditStore.prune(applicationContext)"))
        assertTrue(service.contains("LogDayPolicy.nextMidnight(now)"))
    }

    @Test fun auditExportShowsAgentAndPayloads() {
        val export = McpAuditPolicy.export(listOf(entry("1")))
        assertTrue(export.contains("Agent:"))
        assertTrue(export.contains("Request [redacted"))
        assertTrue(export.contains("Response [redacted"))
        val denied = McpAuditPolicy.export(listOf(entry("2").copy(
            agentName = "Browser (unverified)", httpMethod = "GET",
            endpoint = "/mcp", httpStatus = 401,
            reason = "Bearer authorization missing")))
        assertTrue(denied.contains("HTTP: GET /mcp"))
        assertTrue(denied.contains("Reason: Bearer authorization missing"))
    }
}
