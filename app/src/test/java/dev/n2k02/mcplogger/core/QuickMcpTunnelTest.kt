package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QuickMcpTunnelTest {
    private fun tracker(vararg lines: String) =
        QuickMcpTunnel.ConnectionTracker().also { t -> lines.forEach { t.onLine(it) } }

    @Test fun notificationPolicyReflectsOnlineReconnectingAndOfflineStates() {
        val online = TunnelNotificationPolicy.fromTunnelStatus("Public MCP connected")
        assertEquals(TunnelNotificationState.ONLINE, online.state)
        assertEquals("MCP tunnel online", online.title)
        val reconnecting = TunnelNotificationPolicy.fromTunnelStatus("Tunnel is reconnecting")
        assertEquals(TunnelNotificationState.RECONNECTING, reconnecting.state)
        val offline = TunnelNotificationPolicy.fromTunnelStatus("Stable relay unavailable")
        assertEquals(TunnelNotificationState.OFFLINE, offline.state)
        assertNotEquals(online.colorArgb, offline.colorArgb)
    }

    @Test fun oneFailedConnectionDoesNotMarkTunnelDown() {
        val t = tracker(
            "INF Registered tunnel connection connIndex=0 connection=a ip=1.1.1.1",
            "INF Registered tunnel connection connIndex=1 connection=b ip=1.1.1.2",
            "ERR Serve tunnel error error=\"EOF\" connIndex=1 event=0",
        )
        assertTrue(t.connected)
    }

    @Test fun allConnectionsLostIsReconnectingThenRecovers() {
        val t = tracker(
            "INF Registered tunnel connection connIndex=0",
            "WRN Connection terminated error=\"timeout\" connIndex=0",
        )
        assertFalse(t.connected)
        assertTrue(t.everRegistered)
        t.onLine("INF Registered tunnel connection connIndex=0")
        assertTrue(t.connected)
    }

    @Test fun unrelatedErrorTextIsIgnored() {
        val t = tracker("INF Registered tunnel connection connIndex=0")
        assertFalse(t.onLine("INF Some message mentioning an error string"))
        assertTrue(t.connected)
    }

    @Test fun rejectedSavedTunnelIsDetected() {
        val t = tracker("ERR Register tunnel error from server side error=\"Unauthorized: Invalid tunnel secret\" connIndex=0")
        assertTrue(t.rejected)
        assertFalse(t.connected)
    }

    @Test fun registrationRoundTripsAndRejectsForeignHosts() {
        val r = QuickMcpTunnel.Registration("id", "secret", "acct", "a-b-c.trycloudflare.com", true)
        assertEquals(r, QuickMcpTunnel.Registration.fromJson(r.toJson()))
        assertEquals("https://a-b-c.trycloudflare.com/mcp", r.mcpUrl)
        val bad = r.copy(hostname = "evil.example.com").toJson()
        assertNull(QuickMcpTunnel.Registration.fromJson(bad))
    }

    @Test fun restartRetainsTheSameUrlAndMcpClientKey() {
        val root = "app/src/main/java/dev/n2k02/mcplogger/"
        fun read(p: String) = listOf(File(root + p), File("../$root$p")).first { it.isFile }.readText()
        val tunnel = read("core/QuickMcpTunnel.kt")
        assertTrue(tunnel.contains("noBackupFilesDir"))
        assertTrue(tunnel.contains("if (reg.confirmed) MonitorState.publicMcpUrl.value ="))
        assertTrue(tunnel.contains("StableMcpRelay.publicMcpUrl(context).orEmpty()"))
        assertTrue(tunnel.contains("MonitorState.publicMcpUrl.value = stable.orEmpty()"))
        assertFalse(tunnel.contains("MonitorState.publicMcpUrl.value = stable ?: reg.mcpUrl"))
        val service = read("core/LogMonitorService.kt")
        val reconnect = service.substringAfter("TUNNEL_RESTART -> {")
            .substringBefore("LOCAL_START, START -> {")
        assertTrue(reconnect.contains("QuickMcpTunnel.stop()"))
        assertTrue(reconnect.contains("QuickMcpTunnel.start(applicationContext)"))
        assertFalse(reconnect.contains("QuickMcpTunnel.forget"))
        assertFalse(reconnect.contains("BridgeClient.saveClientToken"))
        assertFalse(service.contains("TUNNEL_ROTATE"))
        val ui = read("LoggerUi.kt")
        assertEquals(1, ui.split("CardHeader(Icons.Rounded.Cloud, " + ('"') + "Public MCP" + ('"')).size - 1)
        assertFalse(ui.contains("Permanent relay (optional)"))
        assertFalse(ui.contains("Rotate URL and access key"))
        assertTrue(ui.contains("Permanent HTTPS MCP URL"))
        assertTrue(ui.contains("MCP client access key"))
    }

    @Test fun firstInitializationRetainsPersistedClientToken() {
        val root = "app/src/main/java/dev/n2k02/mcplogger/core/"
        val src = listOf(File(root + "BridgeClient.kt"), File("../$root" + "BridgeClient.kt"))
            .first { it.isFile }.readText()
        assertTrue(src.contains(".ifBlank { McpConnectionSetup.newSecret() }"))
        assertTrue(src.contains("mcp_client_token"))
    }
}
