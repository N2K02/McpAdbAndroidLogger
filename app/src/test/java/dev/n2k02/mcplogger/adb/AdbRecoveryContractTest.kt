package dev.n2k02.mcplogger.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdbRecoveryContractTest {
    private fun code(name: String): String {
        val path = "app/src/main/java/dev/n2k02/mcplogger/adb/" + name
        return listOf(File(path), File("../" + path)).first { it.isFile }.readText()
    }
    @Test fun matchingVrecRecoveryCadenceAndQuit() {
        val s = code("AdbRecoveryLifecycle.kt")
        assertTrue(s.contains("delay(120_000)"))
        assertTrue(s.contains("15, TimeUnit.MINUTES"))
        assertTrue(s.contains("scope.coroutineContext.cancelChildren()"))
        assertTrue(s.contains("AdbRecoverySettings.backgroundRecoveryAllowed(app)"))
        assertTrue(s.contains("AdbCoordinator.manualRetryRequired.value"))
        assertTrue(s.contains("AdbRecoverySettings.restoreWirelessAfterReboot(app)"))
    }
    @Test fun healthyConnectionIsReusedAndDisconnectDoesNotDestroyIdentity() {
        val coordinator = code("AdbCoordinator.kt")
        val healthy = coordinator.indexOf("if (verified.value && AdbTransport.lastKnownConnected)")
        val firstReconnect = coordinator.indexOf("AdbTransport.close()")
        assertTrue(healthy >= 0)
        assertTrue(firstReconnect > healthy)
        assertTrue(coordinator.contains("existingUid == \"2000\""))
        val adb = code("AdbTransport.kt")
        assertTrue(adb.contains("oldManager?.disconnect()"))
        assertTrue(adb.contains("private val sessionKey: PrivateKey"))
        assertTrue(adb.contains("PKCS8EncodedKeySpec("))
        assertTrue(adb.contains("getPrivateKey(): PrivateKey = sessionKey"))
        assertFalse(adb.contains("getPrivateKey(): PrivateKey = identity.privateKey"))
        assertTrue(adb.contains("suspend fun disconnect() = close()"))
        assertFalse(adb.contains("manager?.close()"))
        assertFalse(adb.contains("mgr.close()"))
    }

    @Test fun connectionStatusVerifiedNotInferredFromPort() {
        val s = code("AdbCoordinator.kt")
        assertTrue(s.contains("AdbTransport.exec(\"id -u\")"))
        assertTrue(s.contains("verified.value = true"))
        assertTrue(s.contains("manualRetryRequired.value"))
        assertTrue(s.contains("AdbRecoverySettings.rememberVerifiedTls(app, port)"))
        assertTrue(s.contains("AdbTransport.switchToUsb()"))
    }
}
