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
            
        assertTrue(s.contains("ShizukuSettingsPage()"))
        assertTrue(s.contains("Page.SHIZUKU"))
        assertTrue(s.contains("SettingsRow(\"ADB\""))
        assertFalse(s.contains("Settings.ACTION_ACCESSIBILITY_SETTINGS"))
        assertFalse(s.contains("Pairing & recovery"))
        assertFalse(s.contains("Connection & security"))
        val adapter = read(src + "adb/ShizukuHostAdapter.kt")
        assertFalse(adapter.contains("onRestoreAfterRestartChanged"))
        assertFalse(adapter.contains("restoreWirelessAfterReboot"))
        assertTrue(adapter.contains("ShizukuSession("))
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
        assertTrue(service.contains("AdbRecoveryLifecycle.start(applicationContext)"))
        assertTrue(service.contains("AdbCoordinator.quit()"))
        assertTrue(service.contains("mcp_server_enabled"))
        assertTrue(service.contains("QuickMcpTunnel.start(applicationContext)"))
        assertTrue(service.contains("QuickMcpTunnel.stop()"))
        assertTrue(main.contains("getBoolean(\"mcp_server_enabled\", true)"))
    }

    @Test fun mcpBootPreferenceIsInConnectionSubmenuAndControlsBootLaunch() {
        val ui = read(src + "LoggerUi.kt").substringAfter("private fun BridgePage(")
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
        assertFalse(read("app/src/main/AndroidManifest.xml").contains("PairingAccessibilityService"))
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
