package dev.n2k02.mcplogger.adb

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AdbVerifiedRecoveryContractTest {
    private fun source(file: String) = listOf(
        File("app/src/main/java/dev/n2k02/mcplogger/$file"),
        File("../app/src/main/java/dev/n2k02/mcplogger/$file")
    ).first { it.isFile }.readText()

    @Test fun shellAlwaysChecksShizukuAndRealUid() {
        val session = source("shizuku/ShizukuSession.kt")
        assertTrue(session.contains("Shizuku.pingBinder()"))
        assertTrue(session.contains("Shizuku.getUid() != Process.SHELL_UID"))
        assertTrue(session.contains("Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED"))
        assertTrue(session.contains("bridge.execute(\"id -u\", 3_000L)"))
        assertTrue(session.contains("throw SecurityException("))
        assertTrue(source("adb/AdbCoordinator.kt").contains("verified.value = state.connected"))
    }

    @Test fun onlyShizukuCanExecuteShellCommands() {
        val transport = source("adb/AdbTransport.kt")
        assertTrue(transport.contains("ShizukuHostAdapter.current()"))
        assertTrue(transport.contains("session.execVerified(command, timeoutMs)"))
        for (obsolete in listOf("pairLocal(", "connectLocalTls(", "switchToTcpip(", "LibAdbWire", "Socket(")) {
            assertFalse(obsolete, transport.contains(obsolete))
        }
        val adapter = source("adb/ShizukuHostAdapter.kt")
        assertTrue(adapter.contains("ShizukuSessionRegistry.install(it)"))
        assertTrue(adapter.contains("ShizukuSessionRegistry.uninstall(it)"))
        assertTrue(adapter.contains("it.quit()"))
    }

    @Test fun failedBinderDoesNotStayConnectedAndQuitStopsRecovery() {
        val session = source("shizuku/ShizukuSession.kt")
        assertTrue(session.contains("Shizuku.OnBinderDeadListener"))
        assertTrue(session.contains("ShizukuPhase.NEEDS_SETUP"))
        assertTrue(session.contains("Shizuku.removeBinderReceivedListener(binderReceived)"))
        val lifecycle = source("adb/AdbRecoveryLifecycle.kt")
        assertTrue(lifecycle.contains("RecoveryWork.cancel(app)"))
        assertTrue(lifecycle.contains("scope.coroutineContext.cancelChildren()"))
    }
}
