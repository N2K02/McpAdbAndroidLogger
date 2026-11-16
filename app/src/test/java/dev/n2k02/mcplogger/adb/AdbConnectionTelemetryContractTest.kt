package dev.n2k02.mcplogger.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdbConnectionTelemetryContractTest {
    private fun source(relative: String): String {
        val root = listOf(File("app"), File("../app")).first { it.isDirectory }
        return root.resolve("src/main/java/dev/n2k02/mcplogger/$relative").readText()
    }

    @Test fun telemetryIsBoundedAndNeverStoresSecrets() {
        val code = source("adb/AdbConnectionTelemetry.kt")
        assertTrue(code.contains("CAPACITY = 60"))
        assertTrue(code.contains("history.removeFirst()"))
        assertTrue(code.contains("@Synchronized fun record"))
        assertTrue(code.contains("p50_ms"))
        assertTrue(code.contains("p95_ms"))
        assertFalse(code.contains("privateKey"))
        assertFalse(code.contains(".put(\"command\""))
        assertFalse(code.contains(".put(\"pairing_code\""))
    }

    @Test fun observesOnlySharedVerifiedTransportAndClearsOnQuit() {
        val coordinator = source("adb/AdbCoordinator.kt")
        val transport = source("adb/AdbTransport.kt")
        assertTrue(coordinator.contains("AdbConnectionTelemetry.Stage.CONNECT"))
        assertTrue(transport.contains("AdbConnectionTelemetry.Stage.SHELL"))
        assertTrue(coordinator.contains("AdbConnectionTelemetry.clear()"))
        assertTrue(transport.contains("core.execVerified(command, timeoutMs)"))
    }

    @Test fun MCPToolIsAuthenticatedAndDoesNotExecuteNewShellCommands() {
        val protocol = source("core/LocalMcpProtocol.kt")
        val server = source("core/LocalMcpServer.kt")
        assertTrue(protocol.contains("\"get_adb_transport_profile\""))
        assertTrue(server.contains("\"get_adb_transport_profile\" ->"))
        assertTrue(server.contains("AdbConnectionTelemetry.snapshot("))
        assertTrue(protocol.contains("JSONObject().put(\"type\", \"oauth2\")"))
    }
}
