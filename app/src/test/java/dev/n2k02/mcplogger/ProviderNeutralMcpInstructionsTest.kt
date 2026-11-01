package dev.n2k02.mcplogger

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProviderNeutralMcpInstructionsTest {
    private fun read(path: String): String =
        listOf(File(path), File("../" + path)).first { it.isFile }.readText()
    @Test fun everyAgentUsesSameFiveStepOAuthFlowInSettings() {
        val src = read("app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt")
            .substringAfter("private fun BridgePage(").substringBefore("private fun LogsPage(")
        assertTrue(src.contains("Connect your AI client"))
        assertTrue(src.contains("Claude, ChatGPT and any compatible MCP agent"))
        assertTrue(src.contains("1. Keep the MCP server and public tunnel running"))
        assertTrue(src.contains("2. In your AI agent's custom MCP connector"))
        assertTrue(src.contains("3. The agent opens this phone-hosted authorization page"))
        assertTrue(src.contains("4. Enter the existing MCP client access key"))
        assertTrue(src.contains("5. The agent connects with its own access token"))
        assertTrue(src.contains("Choose OAuth / automatic") ||
            src.contains("Select OAuth and automatic client registration"))
        assertTrue(src.contains("Authorized MCP agents"))
        assertTrue(src.contains("Revoke"))
        assertFalse(src.contains("Use the separate client key as Bearer authentication"))
    }
    @Test fun oauthRevokeClearsTokensCodesAndPendingApprovals() {
        val src = read("app/src/main/java/dev/n2k02/mcplogger/core/LocalMcpOAuth.kt")
        assertTrue(src.contains("for (name in listOf(\"pending\", \"codes\"))"))
        assertTrue(src.contains("reconcileState(s, digest(BridgeClient.clientToken(c)), issuer(), now)"))
        assertTrue(src.contains("invalidatePending(s, if (keyChanged)"))
        assertTrue(src.contains("if (name == \"pending\") retire(s, key, \"revoked\""))
        assertTrue(src.contains("token_endpoint_auth_methods_supported"))
        assertTrue(src.contains("code_challenge_methods_supported"))
    }
    @Test fun serverRejectsAnonymousMcpAndExposesOAuthDiscovery() {
        val src = read("app/src/main/java/dev/n2k02/mcplogger/core/LocalMcpServer.kt")
        assertTrue(src.contains("LocalMcpOAuth.validate(context, actual)"))
        assertTrue(src.contains("LocalMcpOAuth.register(context, json)"))
        assertTrue(src.contains("LocalMcpOAuth.approve(context, parseParams(body))"))
        assertTrue(src.contains("WWW-Authenticate"))
    }
}
