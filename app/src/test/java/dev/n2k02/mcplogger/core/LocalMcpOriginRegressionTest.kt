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
        assertEquals("strict-origin", LocalMcpServer.referrerPolicy("text/html"))
        assertEquals("no-referrer", LocalMcpServer.referrerPolicy("application/json"))
        assertEquals("no-referrer", LocalMcpServer.referrerPolicy("text/plain"))
        assertTrue(source().contains("Referrer-Policy: " + "\" + referrerPolicy(type)"))
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
