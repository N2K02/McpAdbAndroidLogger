package dev.n2k02.mcplogger

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HomeMcpCardAndStatusUiTest {
    private fun code(name: String): String {
        val p = "app/src/main/java/dev/n2k02/mcplogger/$name"
        return listOf(File(p), File("../$p")).first { it.isFile }.readText()
    }

    @Test fun homeDailyLogcatCardPrecedesReadOnlyMcpConnectionDetails() {
        val home = code("HomeDashboard.kt")
        val today = home.indexOf("\"Logcat (Today)\"")
        val mcp = home.indexOf("\"Public MCP\"")
        assertTrue(today >= 0)
        assertTrue(mcp > today)
        val card = home.substring(mcp, home.indexOf("    }\n}\n\n@Composable\nprivate fun StatMetric"))
        assertTrue(card.contains("Copy public MCP URL"))
        assertTrue(card.contains("Copy MCP client access key"))
        assertFalse(card.contains("OutlinedTextField"))
        assertFalse(card.contains("LOCAL_STOP"))
        assertTrue(card.contains("sensitive = true"))
        assertTrue(card.contains("\"•\".repeat(24)"))
    }

    @Test fun tunnelToneMapsStatuses() {
        assertEquals(Tone.GOOD, tunnelTone("Public MCP connected"))
        assertEquals(Tone.BUSY, tunnelTone("Tunnel is reconnecting"))
        assertEquals(Tone.BUSY, tunnelTone("Connecting to Cloudflare..."))
        assertEquals(Tone.BAD, tunnelTone("Tunnel unavailable; local MCP still works"))
        assertEquals(Tone.BAD, tunnelTone("Stopped"))
        assertEquals(Tone.BAD, tunnelTone("Off"))
        assertEquals(Tone.BAD, tunnelTone("Permanent endpoint not configured"))
    }

    @Test fun localAndAdbTones() {
        assertEquals(Tone.GOOD, localMcpTone("Local server running"))
        assertEquals(Tone.IDLE, localMcpTone("Stopped"))
        assertEquals(Tone.BAD, localMcpTone("Port unavailable"))
        assertEquals(Tone.GOOD, adbTone("UID 2000 verified • 127.0.0.1:5555"))
        assertEquals(Tone.BAD, adbTone("Not connected"))
    }

    @Test fun updaterPageKeepsItsOwnLook() {
        val ui = code("LoggerUi.kt")
        val updates = ui.substringAfter("Page.UPDATES -> {").substringBefore("}\n        }")
        assertFalse(updates.contains("CardHeader"))
        assertTrue(updates.contains("N2K02UpdatePage("))
    }
}
