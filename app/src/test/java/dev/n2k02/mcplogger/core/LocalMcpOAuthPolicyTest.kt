package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Base64

class LocalMcpOAuthPolicyTest {
    @Test fun genericAgentsCanRegisterSecureRedirectsWithoutVendorAllowlist() {
        assertTrue(LocalMcpOAuth.validRedirect("https://my-agent.example/oauth/callback"))
        assertTrue(LocalMcpOAuth.validRedirect("https://chatgpt.com/oauth/callback"))
        assertTrue(LocalMcpOAuth.validRedirect("https://claude.ai/api/mcp/auth_callback"))
        assertTrue(LocalMcpOAuth.validRedirect("http://127.0.0.1:19201/callback"))
        assertFalse(LocalMcpOAuth.validRedirect("http://unknown-agent.example/callback"))
        assertFalse(LocalMcpOAuth.validRedirect("https://user:password@agent.example/callback"))
        assertFalse(LocalMcpOAuth.validRedirect("https://agent.example/callback#unsafe"))
        assertFalse(LocalMcpOAuth.validRedirect("javascript:alert(1)"))
        assertFalse(LocalMcpOAuth.validRedirect("https://agent.example"))
    }

    @Test fun offlineRefreshScopeDoesNotGrantAdditionalOperations() {
        assertTrue(LocalMcpOAuth.validScopes("logcat:read"))
        assertTrue(LocalMcpOAuth.validScopes("logcat:read offline_access"))
        assertTrue(LocalMcpOAuth.validScopes("offline_access logcat:read"))
        assertFalse(LocalMcpOAuth.validScopes("offline_access"))
        assertFalse(LocalMcpOAuth.validScopes("logcat:read logcat:write"))
        assertFalse(LocalMcpOAuth.validScopes("logcat:read logcat:read"))
    }

    @Test fun pkceS256RequiresExactVerifierAndChallenge() {
        val verifier = "correct-verifier-with-valid-length-0123456789abc"
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        assertTrue(LocalMcpOAuth.pkceMatches(verifier, challenge))
        assertFalse(LocalMcpOAuth.pkceMatches("wrong-verifier-with-valid-length-0123456789abc", challenge))
        assertFalse(LocalMcpOAuth.pkceMatches("short", challenge))
        assertFalse(LocalMcpOAuth.pkceMatches(verifier, challenge + "="))
    }

    @Test fun metadataSupportsDynamicRegistrationAndProtectedResource() {
        val previous = MonitorState.publicMcpUrl.value
        try {
            MonitorState.publicMcpUrl.value = "https://live-tunnel.example/mcp"
            val resource = LocalMcpOAuth.metadata("/.well-known/oauth-protected-resource")
            assertEquals(200, resource.status)
            assertEquals("https://live-tunnel.example/mcp",
                JSONObject(resource.body).getString("resource"))
            val auth = LocalMcpOAuth.metadata("/.well-known/oauth-authorization-server")
            assertEquals(200, auth.status)
            val server = JSONObject(auth.body)
            assertEquals("https://live-tunnel.example/oauth/register",
                server.getString("registration_endpoint"))
            assertEquals("https://live-tunnel.example/oauth/token",
                server.getString("token_endpoint"))
            assertEquals("S256", server.getJSONArray("code_challenge_methods_supported").getString(0))
            assertTrue(server.getJSONArray("scopes_supported").toString().contains("offline_access"))
        } finally {
            MonitorState.publicMcpUrl.value = previous
        }
    }
}
