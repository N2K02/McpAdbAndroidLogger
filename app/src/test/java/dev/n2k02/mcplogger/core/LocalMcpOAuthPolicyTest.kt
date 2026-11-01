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

    @Test fun authorizationFailuresHaveSafeReasonsAndShortStateIsAllowed() {
        val resource = "https://example.com/mcp"
        val args = mapOf("response_type" to "code",
            "code_challenge_method" to "S256", "code_challenge" to "a".repeat(43),
            "state" to "x", "resource" to resource, "scope" to "logcat:read")
        assertNull(LocalMcpOAuth.authorizationRequestIssue(args, resource))
        assertNull(LocalMcpOAuth.authorizationRequestIssue(args - "state", resource))
        assertEquals("OAuth resource does not match the advertised /mcp endpoint",
            LocalMcpOAuth.authorizationRequestIssue(
                args + ("resource" to "https://other.example/mcp"), resource))
        assertEquals("PKCE S256 is required",
            LocalMcpOAuth.authorizationRequestIssue(
                args + ("code_challenge_method" to "plain"), resource))
        assertEquals("Unsupported scope; logcat:read required",
            LocalMcpOAuth.authorizationRequestIssue(
                args + ("scope" to "admin"), resource))
    }

    private fun state(expires: Long = 50_000L): JSONObject = JSONObject().apply {
        put("master", "key-one")
        put("issuer", "https://old.example")
        put("pending", JSONObject().put("hashed-ticket", JSONObject()
            .put("expiry", expires).put("attempts", 0)))
        put("codes", JSONObject().put("code", JSONObject().put("expiry", expires)))
        put("tokens", JSONObject().put("access", JSONObject().put("expiry", expires)))
    }

    @Test fun ticketSurvivesUnchangedCredentialsAndTransientTunnelOutage() {
        val db = state()
        assertFalse(LocalMcpOAuth.reconcileState(db, "key-one", "https://old.example", 100L))
        assertFalse(LocalMcpOAuth.reconcileState(db, "key-one", null, 100L))
        assertEquals("valid", LocalMcpOAuth.approvalStatus(db, "hashed-ticket", 100L))
    }

    @Test fun expiryDistinguishesExpiredFromUnknownAndPreservesReplayHistory() {
        val db = state(500L)
        assertTrue(LocalMcpOAuth.expireState(db, 501L))
        assertEquals("expired", LocalMcpOAuth.approvalStatus(db, "hashed-ticket", 502L))
        assertEquals("unknown", LocalMcpOAuth.approvalStatus(db, "other", 502L))
        assertEquals(0, db.getJSONObject("codes").length())
        assertEquals(0, db.getJSONObject("tokens").length())
        assertTrue(LocalMcpOAuth.approvalFailureReason("expired").contains("expired"))
        assertTrue(LocalMcpOAuth.approvalFailureReason("used").contains("completed"))
        assertTrue(LocalMcpOAuth.approvalFailureReason("locked").contains("incorrect"))
    }

    @Test fun masterKeyChangeInvalidatesPendingTicketsAndTokensWithReason() {
        val db = state()
        assertTrue(LocalMcpOAuth.reconcileState(db, "key-two", "https://old.example", 100L))
        assertEquals("key_rotated", LocalMcpOAuth.approvalStatus(db, "hashed-ticket", 101L))
        assertEquals(0, db.getJSONObject("tokens").length())
        assertEquals(0, db.getJSONObject("codes").length())
        assertEquals(0, db.getJSONObject("pending").length())
    }

    @Test fun changedPublicUrlInvalidatesSessionButReconnectDoesNot() {
        val db = state()
        assertTrue(LocalMcpOAuth.reconcileState(db, "key-one", "https://new.example", 100L))
        assertEquals("url_changed", LocalMcpOAuth.approvalStatus(db, "hashed-ticket", 101L))
        assertEquals("https://new.example", db.getString("issuer"))
        assertTrue(LocalMcpOAuth.approvalFailureReason("url_changed").contains("URL"))
    }

    @Test fun replayHistoryIsBoundedAndPruned() {
        val db = state(50L)
        assertTrue(LocalMcpOAuth.expireState(db, 100L))
        assertEquals("expired", LocalMcpOAuth.approvalStatus(db, "hashed-ticket", 101L))
        assertTrue(LocalMcpOAuth.expireState(db, 4_000_000L))
        assertEquals("unknown", LocalMcpOAuth.approvalStatus(db, "hashed-ticket", 4_000_001L))
    }

    @Test fun consentWindowAllowsSwitchingAppsToCopyKey() {
        assertEquals(20L * 60 * 1000, LocalMcpOAuth.APPROVAL_MS)
        assertTrue(LocalMcpOAuth.validApprovalTicket("a".repeat(43)))
        assertFalse(LocalMcpOAuth.validApprovalTicket(""))
        assertFalse(LocalMcpOAuth.validApprovalTicket("<script>"))
    }

    @Test fun invalidKeysAllowRetriesThenLockOnlyTheAffectedTicket() {
        val db = state()
        db.getJSONObject("pending").put("another-ticket",
            JSONObject().put("expiry", 50_000L).put("attempts", 0))
        for (attempt in 1..4) {
            assertEquals(attempt,
                LocalMcpOAuth.rejectApprovalKey(db, "hashed-ticket", 100L))
            assertEquals("valid", LocalMcpOAuth.approvalStatus(db, "hashed-ticket", 100L))
        }
        assertEquals(5, LocalMcpOAuth.rejectApprovalKey(db, "hashed-ticket", 100L))
        assertEquals("locked", LocalMcpOAuth.approvalStatus(db, "hashed-ticket", 101L))
        assertEquals("valid", LocalMcpOAuth.approvalStatus(db, "another-ticket", 101L))
    }

    @Test fun completedConsentIsNeverReusedEvenAfterSerialization() {
        val db = state()
        val reopened = JSONObject(db.toString()) // Simulate app/process restart.
        assertEquals("valid", LocalMcpOAuth.approvalStatus(reopened, "hashed-ticket", 100L))
        LocalMcpOAuth.completeApproval(reopened, "hashed-ticket", 100L)
        assertEquals("used", LocalMcpOAuth.approvalStatus(reopened, "hashed-ticket", 101L))
        val persisted = JSONObject(reopened.toString())
        assertEquals("used", LocalMcpOAuth.approvalStatus(persisted, "hashed-ticket", 101L))
        assertEquals(0, persisted.getJSONObject("pending").length())
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
