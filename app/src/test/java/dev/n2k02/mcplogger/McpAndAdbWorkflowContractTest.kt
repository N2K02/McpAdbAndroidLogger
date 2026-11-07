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

    @Test fun adbPairingStaysOnHomeAndSettingsMatchVrec() {
        val settings = source("app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt")
            .substringAfter("private fun AdbPage(").substringBefore("private fun BridgePage(")
        val home = source("app/src/main/java/dev/n2k02/mcplogger/HomeDashboard.kt")
        val setup = source("app/src/main/java/dev/n2k02/mcplogger/AdbHomeSetupCard.kt")
        assertTrue(home.contains("AdbHomeSetupCard()"))
        assertTrue(setup.contains("SharedAdbHostAdapter.core(context)"))
        assertTrue(setup.contains("AdbHomeSetupPage(adb)"))
        assertTrue(setup.contains("AdbPageLifecycle(adb)"))
        assertTrue(setup.contains("state.connected"))
        assertFalse(settings.contains("Connect Android ADB"))
        assertFalse(settings.contains("Advanced: manual ports"))
        assertTrue(settings.contains("AdbConnectionSettingsPage("))
        assertTrue(settings.contains("PairingAccessibilityService.enabled(context)"))
        // The five controls and two cards live in ONE versioned shared page,
        // rather than duplicated copies in each app.
        assertFalse(settings.contains("Pairing & recovery"))
        assertFalse(settings.contains("Connection & security"))
        val adb = source("app/src/main/java/dev/n2k02/mcplogger/adb/AdbCoordinator.kt")
        assertTrue(adb.contains("val discoveryState = MutableStateFlow"))
        assertTrue(adb.contains("SharedAdbHostAdapter.core(context.applicationContext)"))
        assertTrue(adb.contains("shared.retry(auto = !userInitiated)"))
        val transport = source("app/src/main/java/dev/n2k02/mcplogger/adb/AdbTransport.kt")
        assertTrue(transport.contains("core.execVerified(command, timeoutMs)"))
    }
}
