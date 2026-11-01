package dev.n2k02.mcplogger.core

import java.net.URI
import java.net.URISyntaxException
import java.security.SecureRandom

/** MCP connector setup. A random key is NOT a hosted endpoint. */
object McpConnectionSetup {
    private val random = SecureRandom()
    private val hex = "0123456789abcdef".toCharArray()

    /** 256-bit token for DEVICE_API_KEY or MCP_CLIENT_TOKEN. */
    fun newSecret(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return buildString(bytes.size * 2) {
            bytes.forEach { b ->
                val value = b.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    /**
     * Accept the gateway base address or its /mcp endpoint.
     * Reject URL-embedded credentials, fragments, queries and non-HTTPS URLs.
     */
    fun gatewayBaseOrNull(value: String): String? = try {
        val input = value.trim()
        val uri = URI(input)
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null ||
            uri.port > 65535 || uri.port == 0) null
        else {
            val rawPath = uri.rawPath.orEmpty().trimEnd('/')
            val path = if (rawPath.endsWith("/mcp")) rawPath.removeSuffix("/mcp") else rawPath
            if (path.contains("..") || path.contains("%") || path.endsWith("/ingest")) null
            else "https://" + uri.rawAuthority + path
        }
    } catch (_: URISyntaxException) {
        // Partial text like "https:" must not crash Compose on recomposition.
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun mcpUrlOrNull(value: String): String? =
        gatewayBaseOrNull(value)?.plus("/mcp")

    fun dockerEnvironment(deviceKey: String, clientToken: String): String =
        "DEVICE_API_KEY=$deviceKey\nMCP_CLIENT_TOKEN=$clientToken"
}
