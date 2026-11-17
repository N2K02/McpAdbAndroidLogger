package dev.n2k02.mcplogger.adb

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AdbRecoveryContractTest {
    private fun source(file: String) = listOf(
        File("app/src/main/java/dev/n2k02/mcplogger/$file"),
        File("../app/src/main/java/dev/n2k02/mcplogger/$file")
    ).first { it.isFile }.readText()

    @Test fun foregroundRotationDoesNotResetShizukuQuit() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("if (savedInstanceState == null) AdbRecoverySettings.markForegroundOpened(this)"))
        val settings = source("adb/AdbRecoverySettings.kt")
        assertTrue(settings.contains("setManuallyQuit(context, false)"))
        assertTrue(settings.contains("getBoolean(\"manual_quit\", false)"))
    }

    @Test fun recoveryVerifiesUidAndDoesNotStartWirelessDebugging() {
        val recovery = source("adb/AdbRecoveryLifecycle.kt")
        assertTrue(recovery.contains("delay(120_000)"))
        assertTrue(recovery.contains("15, TimeUnit.MINUTES"))
        assertTrue(recovery.contains("scope.coroutineContext.cancelChildren()"))
        assertTrue(recovery.contains("AdbTransport.exec(\"id -u\""))
        assertFalse(recovery.contains("restoreWirelessAfterReboot"))
        assertFalse(recovery.contains("NetworkCallback"))
        assertFalse(recovery.contains("connectTls"))
    }

    @Test fun noLocalAdbKeyPairOrTlsTransportRemains() {
        val base = File("app/src/main/java/dev/n2k02/mcplogger/adb")
            .takeIf { it.isDirectory } ?: File("../app/src/main/java/dev/n2k02/mcplogger/adb")
        for (name in listOf("AdbIdentity.kt", "AdbSessionKey.kt", "PairingCodeUiPolicy.kt")) {
            assertFalse(base.resolve(name).exists())
        }
        assertTrue(source("adb/AdbTransport.kt").contains("session.execVerified(command, timeoutMs)"))
        assertFalse(source("adb/AdbCoordinator.kt").contains("disableTcp"))
    }

    @Test fun onlyLiveShizukuStatusIsPublished() {
        val coordinator = source("adb/AdbCoordinator.kt")
        assertTrue(coordinator.contains("verified.value = state.connected"))
        assertTrue(coordinator.contains("ShizukuHostAdapter.shutdown()"))
        assertFalse(coordinator.contains("tcpPortOpen"))
        assertFalse(coordinator.contains("pairingPort"))
    }
}
