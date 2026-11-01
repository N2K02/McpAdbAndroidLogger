package dev.n2k02.mcplogger

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Audits must remain a Settings-only page and never log authorization secrets. */
class McpAuditsContractTest {
    private fun source(path: String) = listOf(File(path), File("../" + path))
        .first { it.isFile }.readText()
    private val root = "app/src/main/java/dev/n2k02/mcplogger/"

    @Test fun settingsPageShowsAuditsWithConsistentNavigation() {
        val ui = source(root + "LoggerUi.kt")
        val nav = source(root + "LoggerNavigation.kt")
        assertTrue(ui.contains("SettingsRow(\"MCP audits\""))
        assertTrue(ui.contains("Page.MCP_AUDITS -> McpAuditsScreen(Modifier.padding(padding))"))
        assertTrue(nav.contains("MCP_AUDITS(\"MCP audits\")"))
        assertFalse(ui.contains("NavigationBarItem(selected = LoggerNavigation(page, subpageParent).auditsSelected"))
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
        val section = server.substringAfter("val result = LocalMcpProtocol.execute(request, auditReader,")
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

    @Test fun agentAuditAccessIsOffByDefaultAndLocalSettingsOnly() {
        val access = source(root + "core/McpAgentAuditAccess.kt")
        val protocol = source(root + "core/LocalMcpProtocol.kt")
        val server = source(root + "core/LocalMcpServer.kt")
        val ui = source(root + "LoggerUi.kt")
        val navigation = source(root + "LoggerNavigation.kt")
        assertTrue(access.contains("getBoolean(READ_HISTORY, false)"))
        assertTrue(access.contains("fun setEnabled(context: Context, allowed: Boolean)"))
        assertTrue(ui.contains("SettingsRow(\"Agent permissions\""))
        assertTrue(ui.contains("Page.MCP_AGENT_ACCESS -> AgentPermissionsPage"))
        assertTrue(ui.contains("Switch(checked = enabled"))
        assertTrue(navigation.contains("MCP_AGENT_ACCESS(\"Agent permissions\")"))
        assertTrue(protocol.contains("toolList(auditReader != null)"))
        assertTrue(protocol.contains("val reader = auditReader ?: throw IllegalArgumentException"))
        assertTrue(server.contains("McpAgentAuditAccess.enabled(context)"))
        assertTrue(server.contains("McpAuditStore.readForAgent(context)"))
        assertTrue(server.contains("[MCP audit-history response omitted; read operation recorded]"))
    }

    @Test fun screenOffersAgentToolStatusTimeFiltersAndUserExport() {
        val screen = source(root + "McpAuditsScreen.kt")
        for (token in listOf("All agents", "All tools", "All results", "Any time",
                "McpAuditPolicy.select", "CreateDocument(\"text/plain\")",
                "Tap to inspect request and response", "Copy details", "Clear history",
                "Export", "RoundedCornerShape(50)", "HTTP: ",
                "Reason: ", "Unknown agent")) assertTrue("Missing " + token, screen.contains(token))
    }
}
