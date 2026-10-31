package dev.n2k02.mcplogger.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LocalMcpSecurityContractTest {
    @Test fun boundOnlyToLoopbackAndUsesBearerToken() {
        val path = "app/src/main/java/dev/n2k02/mcplogger/core/LocalMcpServer.kt"
        val src = listOf(File(path), File("../$path")).firstOrNull { it.isFile }!!.readText()
        assertTrue(src.contains("InetAddress.getByName(\"127.0.0.1\")"))
        assertTrue(src.contains("MessageDigest.isEqual"))
        assertTrue(src.contains("origin !in allowedOrigins"))
        assertTrue(src.contains("MAX_BODY"))
        assertTrue(src.contains("Bearer "))
    }

    @Test fun quickTunnelDoesNotPublishUrlBeforeEdgeHandshake() {
        val path = "app/src/main/java/dev/n2k02/mcplogger/core/QuickMcpTunnel.kt"
        val src = listOf(File(path), File("../$path")).firstOrNull { it.isFile }!!.readText()
        assertTrue(src.contains("Registered tunnel connection"))
        assertTrue(src.contains("MonitorState.publicMcpUrl.value = \"\""))
        assertTrue(src.contains("MonitorState.publicMcpUrl.value = \"https://\""))
        assertTrue(src.contains("process?.destroyForcibly()"))
    }
}
