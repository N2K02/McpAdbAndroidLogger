package dev.n2k02.mcplogger

import org.junit.Assert.*
import org.junit.Test

class HomeBadgeAndJsonTest {
    @Test fun badgesReflectReadingAndConnectionStates() {
        assertEquals("Idle", HomeBadgePolicy.logs(false, false).label)
        assertEquals("Ready", HomeBadgePolicy.logs(false, true).label)
        assertEquals("Reading", HomeBadgePolicy.logs(true, true).label)
        assertEquals("Connected", HomeBadgePolicy.adb(true, false).label)
        assertEquals("Reading", HomeBadgePolicy.adb(true, true).label)
        assertEquals("Offline", HomeBadgePolicy.adb(false, true).label)
        assertEquals("Reading", HomeBadgePolicy.mcp("Public MCP connected", true).label)
        assertEquals("Online", HomeBadgePolicy.mcp("Public MCP connected", false).label)
        assertEquals("Running", HomeBadgePolicy.performance(true).label)
    }

    @Test fun jsonIsFormattedEvenWhenTextHoldsNestedJson() {
        val encoded = org.json.JSONObject()
            .put("content", org.json.JSONArray().put(org.json.JSONObject()
                .put("type", "text").put("text", "[{\"id\":1,\"status\":\"OK\"}]")))
        val pretty = AuditJsonFormatter.pretty(encoded.toString())
        assertTrue(pretty.contains("\n"))
        assertTrue(pretty.contains("\"status\""))
        assertTrue(pretty.contains("OK"))
        assertFalse(pretty.contains("\\\"status\\\""))
        assertEquals("plain text", AuditJsonFormatter.pretty("plain text"))
    }
}
