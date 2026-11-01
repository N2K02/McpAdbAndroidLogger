package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Regression: Chrome's OAuth approval POST must not become Origin: null. */
class LocalMcpOriginRegressionTest {
    private fun source(): String {
        val path = "app/src/main/java/dev/n2k02/mcplogger/core/LocalMcpServer.kt"
        return listOf(File(path), File("../$path")).first { it.isFile }.readText()
    }

    @Test fun browserConsentUsesOriginOnlyPolicyInsteadOfNoReferrer() {
        assertEquals("strict-origin", LocalMcpServer.referrerPolicy("text/html"))
        assertEquals("no-referrer", LocalMcpServer.referrerPolicy("application/json"))
        assertEquals("no-referrer", LocalMcpServer.referrerPolicy("text/plain"))
        assertTrue(source().contains("referrerPolicy(type)"))
    }

    @Test fun consentScriptIsSameOriginAndOnlyEnabledOnHtmlConsent() {
        val server = source()
        assertTrue(server.contains("path == \"/oauth/consent.js\" && method == \"GET\""))
        assertTrue(server.contains("LocalMcpOAuth.CONSENT_SCRIPT"))
        assertTrue(server.contains("\"application/javascript\""))
        assertTrue(server.contains("if (type == \"text/html\") \"script-src 'self'; \""))
        val oauthPath = "app/src/main/java/dev/n2k02/mcplogger/core/LocalMcpOAuth.kt"
        val oauth = listOf(File(oauthPath), File("../$oauthPath")).first { it.isFile }.readText()
        assertTrue(oauth.contains("src=\"/oauth/consent.js\" defer"))
        assertTrue(oauth.contains("id=\"access-key\""))
        assertTrue(oauth.contains("id=\"approve-button\""))
        assertTrue(oauth.contains("id=\"approval-form\""))
    }

    @Test fun onlyConsentCanSetARegisteredCrossOriginRedirectInCsp() {
        val redirectCsp = LocalMcpOAuth.consentSecurityPolicy(
            "https://chatgpt.com/oauth/callback")
        val cspHeader = mapOf("Content-Security-Policy" to redirectCsp)
        assertEquals(redirectCsp,
            LocalMcpServer.responseSecurityPolicy("text/html", cspHeader))
        assertEquals("default-src 'none'; style-src 'unsafe-inline'; " +
            "form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
            LocalMcpServer.responseSecurityPolicy("application/json", cspHeader))
        assertTrue(LocalMcpServer.responseSecurityPolicy("text/html", emptyMap())
            .contains("form-action 'self';"))
        val oauthPath = "app/src/main/java/dev/n2k02/mcplogger/core/LocalMcpOAuth.kt"
        val oauth = listOf(File(oauthPath), File("../$oauthPath")).first { it.isFile }.readText()
        assertTrue(oauth.contains("mapOf(\"Content-Security-Policy\" to consentSecurityPolicy(callback))"))
        assertTrue(oauth.contains("consentSecurityPolicy(record.getString(\"redirect\"))"))
        val server = source()
        assertTrue(server.contains("val policy = responseSecurityPolicy(type, extra)"))
        assertTrue(server.contains("!key.equals(\"Content-Security-Policy\", true)"))
    }

    @Test fun untrustedOriginProtectionAndSameOriginOAuthFormRemainInPlace() {
        val server = source()
        assertTrue(server.contains("respond(output, 403, \"Untrusted Origin\")"))
        assertTrue(server.contains("origin != LocalMcpOAuth.publicOrigin()"))
        val oauthPath = "app/src/main/java/dev/n2k02/mcplogger/core/LocalMcpOAuth.kt"
        val oauth = listOf(File(oauthPath), File("../$oauthPath"))
            .first { it.isFile }.readText()
        assertTrue(oauth.contains("method=\"post\" action=\"/oauth/approve\""))
        assertTrue(oauth.contains("type=\"password\""))
    }
}
