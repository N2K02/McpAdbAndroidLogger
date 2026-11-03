package dev.n2k02.mcplogger

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Prevent regression when the user backgrounds the app to open Claude or ChatGPT. */
class AdbVrecAndBackgroundTunnelTest {
    private fun read(path: String) = listOf(File(path), File("../" + path))
        .first { it.isFile }.readText()
    private val src = "app/src/main/java/dev/n2k02/mcplogger/"

    @Test fun settingsMirrorLatestVrecTwoCardsAndActions() {
        val s = read(src + "LoggerUi.kt")
            .substringAfter("private fun AdbPage(").substringBefore("private fun BridgePage(")
        for (name in listOf("Pairing & recovery", "Connection & security",
                "Auto-detect pairing code", "Connection watchdog", "Start on boot",
                "Restore after restart", "Keep ADB without Wi-Fi (TCP/5555)",
                "Disable TCP/5555")) assertTrue(name, s.contains(name))
        assertTrue(s.contains("AdbRecoveryLifecycle.configureWatchdog"))
        assertTrue(s.contains("AdbCoordinator.disableTcp(context)"))
        assertTrue(s.contains("AdbRecoverySettings.grantSecureSettings(context)"))
    }

    @Test fun foregroundTunnelIsIndependentOfTheUiAndNotTimedDataSync() {
        val main = read(src + "MainActivity.kt")
        assertTrue(main.contains("AdbRecoveryLifecycle.start(this)"))
        assertFalse(main.contains("AdbCoordinator.stopDiscovery()"))
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"connectedDevice\""))
        assertFalse(manifest.contains("android:foregroundServiceType=\"dataSync\""))
        val service = read(src + "core/LogMonitorService.kt")
        assertTrue(service.contains("return START_STICKY"))
        assertTrue(service.contains("mcp_server_enabled"))
        assertTrue(service.contains("QuickMcpTunnel.start(applicationContext)"))
        assertTrue(service.contains("QuickMcpTunnel.stop()"))
        assertTrue(main.contains("getBoolean(\"mcp_server_enabled\", true)"))
    }

    @Test fun mcpBootPreferenceIsInConnectionSubmenuAndControlsBootLaunch() {
        val ui = read(src + "LoggerUi.kt").substringAfter("private fun BridgePage()")
        assertTrue(ui.contains("Start MCP on boot"))
        assertTrue(ui.contains("McpBootSettings.startOnBoot(context)"))
        assertTrue(ui.contains("McpBootSettings.setStartOnBoot(context, enabled)"))
        val boot = read(src + "core/BootReceiver.kt")
        assertTrue(boot.contains("McpBootSettings.shouldStartOnBoot"))
        assertTrue(boot.contains("McpBootSettings.startOnBoot(context)"))
        assertTrue(boot.contains("LogMonitorService.LOCAL_START"))
    }

    @Test fun deliberateQuitStopsRecoveryAndTunnel() {
        val main = read(src + "MainActivity.kt")
        assertTrue(main.contains("AdbRecoveryLifecycle.shutdown(this)"))
        val service = read(src + "core/LogMonitorService.kt")
        assertTrue(service.contains("AdbRecoverySettings.setManuallyQuit(applicationContext, true)"))
        assertTrue(service.contains("AdbRecoveryLifecycle.shutdown(applicationContext)"))
        assertTrue(service.contains("LocalMcpServer.stop()"))
        val pairing = read(src + "adb/PairingAccessibilityService.kt")
        assertTrue(pairing.contains("AdbRecoverySettings.autoPair(this)"))
    }

    @Test fun tunnelSupervisionDoesNotDependOnUiVisibility() {
        val tunnel = read(src + "core/QuickMcpTunnel.kt")
        assertTrue(tunnel.contains("while (!stopped)"))
        assertTrue(tunnel.contains("delay(backoff)"))
        assertTrue(tunnel.contains("ConnectionTracker"))
        assertTrue(tunnel.contains("reg.confirmed"))
        val boot = read(src + "core/BootReceiver.kt")
        assertTrue(boot.contains("mcp_server_enabled"))
        assertTrue(boot.contains("LOCAL_START"))
    }
}
