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
        assertTrue(setup.contains("AdbCoordinator.discoveryState.collectAsState()"))
        assertTrue(setup.contains("Open Android Developer options"))
        assertTrue(setup.contains("Pair and connect ADB"))
        assertTrue(setup.contains("Reconnect / verify ADB"))
        assertFalse(settings.contains("Connect Android ADB"))
        assertFalse(settings.contains("Advanced: manual ports"))
        for (name in listOf("Pairing & recovery", "Connection & security",
                "Auto-detect pairing code", "Connection watchdog", "Start on boot",
                "Restore after restart", "Keep ADB without Wi-Fi (TCP/5555)",
                "Disable TCP/5555"))
            assertTrue(name, settings.contains(name))
        val adb = source("app/src/main/java/dev/n2k02/mcplogger/adb/AdbCoordinator.kt")
        assertTrue(adb.contains("val discoveryState = MutableStateFlow"))
        assertTrue(adb.contains("SharedAdbHostAdapter.core(context.applicationContext)"))
        assertTrue(adb.contains("shared.retry(auto = !userInitiated)"))
        val transport = source("app/src/main/java/dev/n2k02/mcplogger/adb/AdbTransport.kt")
        assertTrue(transport.contains("core.execVerified(command, timeoutMs)"))
    }
}
