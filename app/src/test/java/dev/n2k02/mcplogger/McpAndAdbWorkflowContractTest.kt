package dev.n2k02.mcplogger

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class McpAndAdbWorkflowContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }
            ?.readText() ?: error("Missing $path")

    @Test fun localMcpIsDirectAndNoLongerUploadsToGateway() {
        val ui = source("app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt")
            .substringAfter("private fun BridgePage(").substringBefore("private fun LogsPage(")
        assertTrue(ui.contains("Phone-hosted server:"))
        assertTrue(ui.contains("Copy permanent MCP URL"))
        assertFalse(ui.contains("Enable log upload"))
        assertFalse(ui.contains("Save MCP connection"))
        val service = source("app/src/main/java/dev/n2k02/mcplogger/core/LogMonitorService.kt")
        assertTrue(service.contains("LocalMcpServer.start(applicationContext)"))
        assertTrue(service.contains("QuickMcpTunnel.start(applicationContext)"))
        assertFalse(service.contains("BridgeClient.upload("))
        assertTrue(service.contains("QuickMcpTunnel.stop()"))
    }

    @Test fun adbPairingStaysInSettingsAndMcpDoesNotRequireHomeControls() {
        val settings = source("app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt")
            
        val home = source("app/src/main/java/dev/n2k02/mcplogger/HomeDashboard.kt")
        val setup = source("app/src/main/java/dev/n2k02/mcplogger/ShizukuSettingsPage.kt")
        assertTrue(home.contains("HomeConnectionTile(\"ADB\""))
        assertFalse(home.contains("AdbHomeSetupCard()"))
        assertTrue(setup.contains("readShizukuAccessStatus()"))
        assertTrue(setup.contains("rememberShizukuAccessStatus()"))
        assertFalse(setup.contains("AdbHomeSetupPage("))
        assertFalse(setup.contains("AdbPageLifecycle("))
        assertTrue(settings.contains("Page.SHIZUKU"))
        assertFalse(settings.contains("Connect Android ADB"))
        assertFalse(settings.contains("Advanced: manual ports"))
        assertTrue(settings.contains("ShizukuSettingsPage()"))
        assertFalse(settings.contains("PairingAccessibilityService.enabled(context)"))
        // The five controls and two cards live in ONE versioned shared page,
        // rather than duplicated copies in each app.
        assertFalse(settings.contains("Pairing & recovery"))
        assertFalse(settings.contains("Connection & security"))
        val adb = source("app/src/main/java/dev/n2k02/mcplogger/adb/AdbCoordinator.kt")
        assertTrue(adb.contains("ShizukuHostAdapter.core(context.applicationContext)"))
        assertTrue(adb.contains("session.retry(auto = !userInitiated)"))
        val transport = source("app/src/main/java/dev/n2k02/mcplogger/adb/AdbTransport.kt")
        assertTrue(transport.contains("session.execVerified(command, timeoutMs)"))
        assertFalse(transport.contains("connectLocalTls("))
        assertFalse(transport.contains("pairLocal("))
    }
}
