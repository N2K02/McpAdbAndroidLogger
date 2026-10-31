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

    @Test fun auditExportShowsAgentAndPayloads() {
        val export = McpAuditPolicy.export(listOf(entry("1")))
        assertTrue(export.contains("Agent:"))
        assertTrue(export.contains("Request [redacted"))
        assertTrue(export.contains("Response [redacted"))
    }
}
