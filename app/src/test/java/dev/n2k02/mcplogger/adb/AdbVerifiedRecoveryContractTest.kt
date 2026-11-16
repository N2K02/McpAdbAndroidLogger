package dev.n2k02.mcplogger.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Regression checks for the one-transport shared ADB runtime cutover. */
class AdbVerifiedRecoveryContractTest {
    private fun source(name: String): String {
        val file = "app/src/main/java/dev/n2k02/mcplogger/adb/" + name
        return listOf(File(file), File("../" + file)).first { it.isFile }.readText()
    }

    @Test fun allMcpShellCommandsUseSharedFreshUidVerification() {
        val transport = source("AdbTransport.kt")
        assertTrue(transport.contains("ShizukuHostAdapter.current()?.let"))
        assertTrue(transport.contains("core.execVerified(command, timeoutMs)"))
        assertTrue(transport.contains("ShizukuHostAdapter.current() == null"))
        val coordinator = source("AdbCoordinator.kt")
        assertTrue(coordinator.contains("ShizukuHostAdapter.core(context.applicationContext)"))
        assertTrue(coordinator.contains("verified.value = state.connected"))
        assertFalse(coordinator.contains("AdbTransport.connect("))
        assertFalse(coordinator.contains("AdbTransport.pairLocal("))
    }

    @Test fun tcpAndTlsRecoveryAndQuitAreOwnedByOneSharedCore() {
        val coordinator = source("AdbCoordinator.kt")
        assertTrue(coordinator.contains("shared.retry(auto = !userInitiated)"))
        assertTrue(coordinator.contains("shared.connectTls(preferred)"))
        assertTrue(coordinator.contains("shared.disableTcp()"))
        assertTrue(coordinator.contains("ShizukuHostAdapter.shutdown()"))
        val adapter = source("ShizukuHostAdapter.kt")
        assertTrue(adapter.contains("ShizukuSessionRegistry.install(it)"))
        assertTrue(adapter.contains("ShizukuSessionRegistry.uninstall(it)"))
        assertTrue(adapter.contains("it.quit()"))
        val recovery = source("AdbRecoveryLifecycle.kt")
        assertTrue(recovery.contains("core.setOption(ShizukuOption.WATCHDOG, enabled)"))
        assertTrue(recovery.contains("never launch two watchdogs"))
    }

    @Test fun listenerOpenAloneCannotClaimShellUid2000() {
        val coordinator = source("AdbCoordinator.kt")
        assertTrue(coordinator.contains("state.connected"))
        assertTrue(coordinator.contains("state.transport == ShizukuTransportMode.LOCAL_TCP_5555"))
        assertTrue(coordinator.contains("check(connected && shared.state.value.connected)"))
        assertTrue(coordinator.contains("AdbRecoverySettings.manuallyQuit(app)"))
        assertTrue(coordinator.contains("shared.acquireService()"))
        assertTrue(coordinator.contains("observedCore?.releaseService()"))
        assertTrue(coordinator.contains("serviceOwner = false"))
        assertTrue(coordinator.contains("if (userInitiated)"))
        assertTrue(coordinator.contains("discover(app)"))
        val adapter = source("ShizukuHostAdapter.kt")
        assertTrue(adapter.contains("RecoveryWork.schedule(app)"))
        assertTrue(adapter.contains("RecoveryWork.cancel(app)"))
    }
}
