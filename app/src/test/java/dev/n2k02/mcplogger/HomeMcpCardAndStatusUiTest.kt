package dev.n2k02.mcplogger

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HomeMcpCardAndStatusUiTest {
    private fun code(name: String): String {
        val p = "app/src/main/java/dev/n2k02/mcplogger/$name"
        return listOf(File(p), File("../$p")).first { it.isFile }.readText()
    }

    @Test fun homeShowsManualLogcatAndMcpReadCountersSeparately() {
        val home = code("HomeDashboard.kt")
        assertTrue(home.contains("Manual Logcat (Today)"))
        assertTrue(home.contains("MCP activity"))
        assertTrue(home.contains("MCP Logcat reads"))
        assertTrue(home.contains("MonitorState.mcpReadCount.collectAsState()"))
        assertFalse(home.contains("HomeTab.PERFORMANCE"))
        assertFalse(home.contains("HomeTab.CONNECTION"))
    }

    @Test fun homeConnectionAndActivityLayoutIsCompactAndSeparatesManualFromMcp() {
        val home = code("HomeDashboard.kt")
        assertTrue(home.contains("Connection status"))
        assertTrue(home.contains("HomeConnectionTile(\"ADB\""))
        assertTrue(home.contains("HomeConnectionTile(\"MCP\""))
        assertTrue(home.contains("HomeInlineMetric(\"Captured today\""))
        assertTrue(home.contains("HomeInlineMetric(\"MCP Logcat reads\""))
        assertTrue(home.contains("Button(onClick = onLogs"))
        assertTrue(home.contains("Text(\"Manual Logs\""))
        assertTrue(home.contains("onClick = { LogMonitorService.resetHomeStatistics() }"))
        assertFalse(home.contains("HomeStatusCard(Icons.Rounded.Terminal"))
        assertFalse(home.contains("HomeStatusCard(Icons.Rounded.Cloud"))
        assertFalse(home.contains("Text(\"ADB: \""))
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
