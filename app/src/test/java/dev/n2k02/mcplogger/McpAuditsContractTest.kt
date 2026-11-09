package dev.n2k02.mcplogger

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Local-only audit journal has its own second main-navigation tab. */
class McpAuditsContractTest {
    private fun source(path: String) = listOf(File(path), File("../" + path))
        .first { it.isFile }.readText()
    private val root = "app/src/main/java/dev/n2k02/mcplogger/"

    @Test fun auditsIsSecondMainNavigationPage() {
        val ui = source(root + "LoggerUi.kt")
        val nav = source(root + "LoggerNavigation.kt")
        assertTrue(ui.contains("LoggerNavigation(page, subpageParent).auditsSelected"))
        assertTrue(ui.contains("Page.MCP_AUDITS -> McpAuditsScreen(Modifier.padding(padding))"))
        assertTrue(nav.contains("MCP_AUDITS(\"MCP audits\")"))
        assertFalse(ui.contains("SettingsRow(\"MCP audits\""))
        assertFalse(ui.contains("SettingsRow(\"Agent permissions\""))
    }

    @Test fun providerAttributionAndDeniedMcpCallsAreCapturedWithoutTokens() {
        val server = source(root + "core/LocalMcpServer.kt")
        val oauth = source(root + "core/LocalMcpOAuth.kt")
        assertTrue(server.contains("McpAuditStore.record(context, agent"))
        assertTrue(server.contains("auditOAuth(context, method, path"))
        assertTrue(server.contains("[OAuth request parameters withheld]"))
        assertTrue(server.contains("LocalMcpOAuth.clientForToken(context, actual)"))
        assertTrue(server.contains("McpAuditStore.DIRECT"))
        assertTrue(server.contains("McpAuditStore.recordDenied(context, method, path"))
        assertTrue(server.contains("clientHint = McpAuditPolicy.clientHint"))
        assertTrue(server.contains("httpStatus = 403"))
        assertTrue(server.contains("httpStatus = if (result == null) 202 else 200"))
        val store = source(root + "core/McpAuditStore.kt")
        assertTrue(store.contains("[unauthenticated request; payload withheld]"))
        assertTrue(store.contains("lastDeniedAtMs"))
        assertTrue(oauth.contains("fun clientForToken(c: Context, token: String)"))
        val section = server.substringAfter("val result = LocalMcpProtocol.execute(request,")
            .substringBefore("} catch (_: Exception)")
        assertTrue(section.contains("McpAuditStore.record"))
        assertTrue(section.contains("responseBody"))
    }

    @Test fun privateBoundedJournalNeverCapturesHeaderOrOauthForm() {
        val store = source(root + "core/McpAuditStore.kt")
        assertTrue(store.contains("c.noBackupFilesDir"))
        assertTrue(store.contains("AtomicFile"))
        assertTrue(store.contains("McpAuditPolicy.snapshot"))
        assertTrue(store.contains("McpAuditPolicy.KEEP"))
        assertFalse(store.contains("headers["))
        val policy = source(root + "core/McpAuditPolicy.kt")
        assertTrue(policy.contains("MAX_REQUEST_CHARS"))
        assertTrue(policy.contains("MAX_RESPONSE_CHARS"))
        assertTrue(policy.contains("private val secretName"))
    }

    @Test fun oauthRecoveryRequiresUserConfirmationAndKeyIsReadOnly() {
        val ui = source(root + "LoggerUi.kt")
        val oauth = source(root + "core/LocalMcpOAuth.kt")
        assertTrue(ui.contains("Reset all OAuth authorizations?"))
        assertTrue(ui.contains("resetAuthorizations(context)"))
        assertTrue(ui.contains("onValueChange = {}, readOnly = true"))
        assertTrue(oauth.contains("AtomicFile(stateFile(c)).delete()"))
        assertFalse(ui.contains("onValueChange = { BridgeClient.saveClientToken(context, it) }"))
    }

    @Test fun remoteAuditToolIsUnavailableAndLocalJournalRemains() {
        val protocol = source(root + "core/LocalMcpProtocol.kt")
        val server = source(root + "core/LocalMcpServer.kt")
        val ui = source(root + "LoggerUi.kt")
        assertFalse(protocol.contains("get_mcp_audits"))
        assertFalse(server.contains("McpAuditStore.readForAgent(context)"))
        assertFalse(ui.contains("AgentPermissionsPage("))
        assertTrue(server.contains("McpAuditStore.record(context, agent"))
        assertTrue(ui.contains("McpAuditsScreen("))
    }

    @Test fun screenOffersAgentToolStatusTimeFiltersAndUserExport() {
        val screen = source(root + "McpAuditsScreen.kt")
        for (token in listOf(
                "All agents", "All tools", "All results", "Any time",
                "McpAuditPolicy.select", "CreateDocument(\"text/plain\")",
                "Copy details", "Clear history", "Export matching audits",
                "RoundedCornerShape(50)", "HTTP: ", "Reason: ", "Unknown agent"
        )) assertTrue("Missing " + token, screen.contains(token))
    }

    @Test fun compactAuditLayoutKeepsFiltersAndActionsAccessible() {
        val screen = source(root + "McpAuditsScreen.kt")
        // No wide scrolling row that clips the fourth filter offscreen.
        assertFalse(screen.contains("Row(Modifier.horizontalScroll"))
        for (token in listOf(
                "Modifier.weight(1f)", "maxLines = 1",
                "overflow = TextOverflow.Ellipsis", "RoundedCornerShape(18.dp)",
                "Icons.Rounded.MoreVert", "contentDescription = \"Audit actions\"",
                "Reset filters", "Export matching audits", "Clear audit history",
                "About MCP audits", "showAuditInfo", "confirmClear = true",
                "snapshot = visible.toList()", "McpAuditPolicy.export(copyOfRows)",
                "AuditJsonPreview(entry.request)", "AuditJsonPreview(entry.response"
        )) assertTrue("Missing " + token, screen.contains(token))
        assertFalse(screen.contains("MCP activity by AI agent · Today only"))
        assertFalse(screen.contains("Tap to inspect request and response"))
        assertFalse(screen.contains("Text(\"Reset\")"))
        assertFalse(screen.contains("Text(\"Export\")"))
    }
}
