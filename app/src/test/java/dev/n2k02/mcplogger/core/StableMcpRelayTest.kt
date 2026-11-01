package dev.n2k02.mcplogger.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableMcpRelayTest {
    @Test fun allowsOnlySecureHostedWorkerMcpUrl() {
        assertTrue(StableMcpRelay.validPublicUrl(
            "https://n2k02-mcp-adb-logger.account.workers.dev/mcp"))
        listOf(
            "https://n2k02.github.io/McpAdbAndroidLogger/mcp",
            "http://mcp.account.workers.dev/mcp",
            "https://mcp.account.workers.dev/health",
            "https://mcp.account.workers.dev/mcp?token=secret",
            "https://mcp.account.workers.dev/mcp#secret",
            "https://x.workers.dev.evil.test/mcp",
            "https://name:password@x.workers.dev/mcp",
            "https://x.workers.dev:8080/mcp",
            "https://127.0.0.1/mcp",
            "not a url",
            "",
        ).forEach { assertFalse(it, StableMcpRelay.validPublicUrl(it)) }
    }
}
