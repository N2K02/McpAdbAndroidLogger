package dev.n2k02.mcplogger.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdbVerifiedRecoveryContractTest {
    private fun source(name: String): String {
        val file = "app/src/main/java/dev/n2k02/mcplogger/adb/" + name
        return listOf(File(file), File("../" + file)).first { it.isFile }.readText()
    }

    @Test fun successfulTransportIsRecordedAndOnlyUid2000IsAuthorized() {
        val transport = source("AdbTransport.kt")
        assertTrue(transport.contains("lastKnownConnected = mgr.isConnected"))
        assertTrue(transport.contains("lastKnownConnected = true"))
        val coordinator = source("AdbCoordinator.kt")
        assertTrue(coordinator.contains("AdbTransport.exec(\"id -u\")"))
        assertTrue(coordinator.contains("check(output == \"2000\")"))
    }

    @Test fun tcpPromotionWaitsForDaemonRestartAndReverifies() {
        val coordinator = source("AdbCoordinator.kt")
        assertTrue(coordinator.contains("for (attempt in 1..5)"))
        assertTrue(coordinator.contains("if (!tcpListening()) continue"))
        assertTrue(coordinator.contains("verified.value = false"))
        assertTrue(coordinator.contains("TCP/5555 did not become an authorized shell"))
        assertTrue(coordinator.contains("manualRetryRequired.value && !userInitiated && !tcpListening()"))
        val recovery = source("AdbRecoveryLifecycle.kt")
        assertTrue(recovery.contains("!AdbCoordinator.isTcpListening()"))
    }

    @Test fun usbDebuggingAloneIsNotMistakenForAuthorization() {
        val coordinator = source("AdbCoordinator.kt")
        assertTrue(coordinator.contains("5555.takeIf { tcpListening() }"))
        assertTrue(coordinator.contains("No local ADB port"))
        assertTrue(coordinator.contains("verified.value = false"))
    }
}
