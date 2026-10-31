package dev.n2k02.mcplogger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard against regressions that make generated MCP credentials hard to share. */
class McpCopyControlsContractTest {
    private fun source(): String {
        val path = "app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt"
        return listOf(File(path), File("../$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")
    }

    @Test fun copyActionsAreInlineWithTheEditableFields() {
        val bridge = source().substringAfter("private fun BridgePage(")
            .substringBefore("private fun LogsPage(")
        for ((field, label) in listOf(
            "url" to "Copy gateway URL",
            "key" to "Copy device API key",
            "clientToken" to "Copy MCP client token",
        )) {
            val section = bridge.substringAfter("OutlinedTextField($field,")
                .substringBefore("OutlinedTextField(", missingDelimiterValue = "")
            assertTrue("Missing inline trailing icon for $field", section.contains("trailingIcon = {"))
            assertTrue("Missing labelled copy icon for $field", section.contains("contentDescription = \"$label\""))
            assertTrue("Copy must be disabled on empty $field", section.contains("enabled = $field.isNotBlank()"))
        }
    }

    @Test fun mcpUrlHasItsOwnAccessibleInlineCopyIcon() {
        val source = source()
        assertTrue(source.contains("contentDescription = \"Copy MCP URL\""))
        assertTrue(source.contains("copy(\"MCP URL\", mcpUrl)"))
    }

    @Test fun secretCopyDoesNotRevealTheMaskedTextFieldOrClipboardPreview() {
        val bridge = source().substringAfter("private fun BridgePage(")
            .substringBefore("private fun LogsPage(")
        assertTrue(bridge.contains("copy(\"Device API key\", key, sensitive = true)"))
        assertTrue(bridge.contains("copy(\"MCP client token\", clientToken, sensitive = true)"))
        assertTrue(bridge.contains("ClipDescription.EXTRA_IS_SENSITIVE"))
        assertTrue(bridge.contains("PasswordVisualTransformation()"))
        assertTrue(bridge.contains("if (value.isBlank()) return"))
    }
}
