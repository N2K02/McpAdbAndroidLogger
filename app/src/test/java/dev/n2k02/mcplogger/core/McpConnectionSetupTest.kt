package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test

class McpConnectionSetupTest {
    @Test fun keysAre256BitHexAndDistinct() {
        val first = McpConnectionSetup.newSecret()
        val second = McpConnectionSetup.newSecret()
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertTrue(second.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(first, second)
    }

    @Test fun baseAndMcpUrlsProduceTheSameConnectorEndpoint() {
        assertEquals("https://gateway.example.com/mcp",
            McpConnectionSetup.mcpUrlOrNull("https://gateway.example.com"))
        assertEquals("https://gateway.example.com/mcp",
            McpConnectionSetup.mcpUrlOrNull("https://gateway.example.com/mcp"))
        assertEquals("https://gateway.example.com/team/mcp",
            McpConnectionSetup.mcpUrlOrNull("https://gateway.example.com/team/"))
        assertEquals("https://gateway.example.com:8443/mcp",
            McpConnectionSetup.mcpUrlOrNull("https://gateway.example.com:8443"))
    }

    @Test fun invalidUrlsDoNotBecomeConnectorUrls() {
        for (url in listOf("", "not-a-url", "http://gateway.example.com",
                "https://user:secret@gateway.example.com", "https://gateway.example.com?key=123",
                "https://gateway.example.com/#foo", "https://gateway.example.com/../bad",
                "https://gateway.example.com/%2e%2e", "https://gateway.example.com/ingest")) {
            assertNull("Accepted unsafe URL: $url", McpConnectionSetup.mcpUrlOrNull(url))
        }
    }

    @Test fun generatedServerConfigUsesBothSeparateTokens() {
        val config = McpConnectionSetup.dockerEnvironment("device123", "client456")
        assertTrue(config.contains("DEVICE_API_KEY=device123"))
        assertTrue(config.contains("MCP_CLIENT_TOKEN=client456"))
        assertFalse(config.contains("client456/mcp"))
    }
}
