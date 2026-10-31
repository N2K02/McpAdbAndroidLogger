package dev.n2k02.mcplogger

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class McpAndAdbWorkflowContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }
            ?.readText() ?: error("Missing $path")

    @Test fun mcpSettingsAutoSaveAndNeverExposeAnUploadToggle() {
        val ui = source("app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt")
            .substringAfter("private fun BridgePage(").substringBefore("private fun LogsPage(")
        assertTrue(ui.contains("fun save() = BridgeClient.autoSave(context, url, key, clientToken)"))
        assertTrue(ui.contains("url = it\n            save()"))
        assertTrue(ui.contains("key = it; save()"))
        assertTrue(ui.contains("clientToken = it; save()"))
        assertTrue(ui.contains("Generate / copy MCP URL"))
        assertFalse(ui.contains("Enable log upload"))
        assertFalse(ui.contains("Save MCP connection"))
        val bridge = source("app/src/main/java/dev/n2k02/mcplogger/core/BridgeClient.kt")
        assertTrue(bridge.contains("base != null && key.isNotBlank()"))
        assertTrue(bridge.contains(".putString(\"gateway_draft\", url)"))
        val activity = source("app/src/main/java/dev/n2k02/mcplogger/MainActivity.kt")
        assertTrue(activity.contains("BridgeClient.initialize(this)"))
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
