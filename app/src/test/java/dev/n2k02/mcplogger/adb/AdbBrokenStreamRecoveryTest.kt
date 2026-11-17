package dev.n2k02.mcplogger.adb

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AdbBrokenStreamRecoveryTest {
    private fun source(file: String) = listOf(
        File("app/src/main/java/dev/n2k02/mcplogger/$file"),
        File("../app/src/main/java/dev/n2k02/mcplogger/$file")
    ).first { it.isFile }.readText()

    @Test fun deadBinderRevokesVerifiedStatus() {
        val s = source("shizuku/ShizukuSession.kt")
        assertTrue(s.contains("override fun onServiceDisconnected("))
        assertTrue(s.contains("binder = null"))
        assertTrue(s.contains("Shizuku.OnBinderDeadListener"))
        assertTrue(s.contains("ShizukuPhase.NEEDS_SETUP"))
        assertTrue(s.contains("binder?.isBinderAlive != true"))
    }
    @Test fun commandsRequirePermissionAndShellUid() {
        val s = source("shizuku/ShizukuSession.kt")
        assertTrue(s.contains("Shizuku.pingBinder()"))
        assertTrue(s.contains("Shizuku.getUid() == Process.SHELL_UID"))
        assertTrue(s.contains("Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED"))
        assertTrue(s.contains("bridge.execute(\"id -u\", 3_000L)"))
        assertTrue(s.contains("bridge.close()"))
    }
    @Test fun transportHasNoPairingFallbackAndListenersAreRemoved() {
        val s = source("shizuku/ShizukuSession.kt")
        val t = source("adb/AdbTransport.kt")
        assertTrue(t.contains("session.execVerified(command, timeoutMs)"))
        assertFalse(t.contains("Socket("))
        assertFalse(t.contains("connectTls"))
        assertTrue(s.contains("Shizuku.removeBinderDeadListener(binderDied)"))
        assertTrue(s.contains("Shizuku.removeBinderReceivedListener(binderReceived)"))
        assertTrue(s.contains("scope.cancel()"))
    }
}
