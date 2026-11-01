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

    @Test fun adbHasLiveDiscoveryAndVrecStylePairing() {
        val ui = source("app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt")
            .substringAfter("private fun AdbPage(").substringBefore("private fun BridgePage(")
        assertTrue(ui.contains("AdbCoordinator.discoveryState.collectAsState()"))
        assertTrue(ui.contains("Open Android Developer options"))
        assertTrue(ui.contains("Pair and connect ADB"))
        assertTrue(ui.contains("UID 2000"))
        assertTrue(ui.contains("Reconnect / verify ADB"))
        assertTrue(ui.contains("Hide advanced port controls"))
        val adb = source("app/src/main/java/dev/n2k02/mcplogger/adb/AdbCoordinator.kt")
        assertTrue(adb.contains("val discoveryState = MutableStateFlow"))
        assertTrue(adb.contains("connectAsync(context.applicationContext)"))
        assertTrue(adb.contains("id -u"))
        assertTrue(adb.contains("2000"))
    }
}
