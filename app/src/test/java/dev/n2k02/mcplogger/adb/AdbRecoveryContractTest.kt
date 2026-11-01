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
    @Test fun connectionStatusVerifiedNotInferredFromPort() {
        val s = code("AdbCoordinator.kt")
        assertTrue(s.contains("AdbTransport.exec(\"id -u\")"))
        assertTrue(s.contains("verified.value = true"))
        assertTrue(s.contains("manualRetryRequired.value"))
        assertTrue(s.contains("AdbRecoverySettings.rememberVerifiedTls(app, port)"))
        assertTrue(s.contains("AdbTransport.switchToUsb()"))
    }
}
