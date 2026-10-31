package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QuickMcpTunnelTest {
    private fun tracker(vararg lines: String) =
        QuickMcpTunnel.ConnectionTracker().also { t -> lines.forEach { t.onLine(it) } }

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

    @Test fun urlIsPersistedAndRotationForgetsItAndKey() {
        val root = "app/src/main/java/dev/n2k02/mcplogger/"
        fun read(p: String) = listOf(File(root + p), File("../$root$p")).first { it.isFile }.readText()
        val tunnel = read("core/QuickMcpTunnel.kt")
        assertTrue(tunnel.contains("noBackupFilesDir"))
        assertFalse(tunnel.contains("credentials?.delete()"))
        val service = read("core/LogMonitorService.kt")
        val rotate = service.substringAfter("TUNNEL_RESTART, TUNNEL_ROTATE -> {")
            .substringBefore("LOCAL_START, START -> {")
        assertTrue(rotate.contains("QuickMcpTunnel.forget(applicationContext)"))
        assertTrue(rotate.contains("BridgeClient.saveClientToken"))
        assertTrue(read("LoggerUi.kt").contains("Rotate URL and access key"))
    }
}
