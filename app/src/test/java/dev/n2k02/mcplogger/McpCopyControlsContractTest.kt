package dev.n2k02.mcplogger

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class McpCopyControlsContractTest {
    private fun source() = listOf(
        File("app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt"),
        File("../app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt")
    ).first { it.isFile }.readText().substringAfter("private fun BridgePage(")
        .substringBefore("private fun LogsPage(")

    @Test fun allUrlsAndKeysHaveInlineCopyButtons() {
        val ui = source()
        for (label in listOf("Copy permanent MCP URL",
                "Copy MCP client access key")) {
            assertTrue("Missing " + label, ui.contains("contentDescription = \"$label\""))
        }
        assertTrue(ui.contains("trailingIcon = {"))
        assertTrue(ui.contains("copy(\"MCP client access key\", clientToken, sensitive = true)"))
        assertTrue(ui.contains("ClipDescription.EXTRA_IS_SENSITIVE"))
        assertTrue(ui.contains("PasswordVisualTransformation()"))
    }

    @Test fun publicUrlIsOnlyCopyableWhenConnected() {
        val ui = source()
        assertTrue(ui.contains("enabled = !stableUrl.isNullOrBlank()"))
        assertTrue(ui.contains("MonitorState.publicMcpUrl.collectAsState()"))
        assertTrue(ui.contains("readOnly = !editingPermanentUrl"))
        assertFalse(ui.contains("BridgeClient.saveClientToken(context, it)"))
    }
}
