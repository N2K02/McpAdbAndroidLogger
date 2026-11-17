package dev.n2k02.mcplogger.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A dead Shizuku binder must never leave a verified shell or use an ADB socket fallback. */
class AdbBrokenStreamRecoveryTest {
    private fun read(relative: String): String =
        listOf(File(relative), File("../$relative")).first { it.isFile }.readText()

    private val transport = "app/src/main/java/dev/n2k02/mcplogger/adb/AdbTransport.kt"
    private val session = "app/src/main/java/dev/n2k02/mcplogger/shizuku/ShizukuSession.kt"

    @Test fun deadBinderClearsItsSessionAndDemotesVerifiedStatus() {
        val source = read(session)
        assertTrue(source.contains("override fun onServiceDisconnected("))
        assertTrue(source.contains("binder = null"))
        assertTrue(source.contains("Shizuku.OnBinderDeadListener"))
        assertTrue(source.contains("ShizukuPhase.NEEDS_SETUP"))
        assertTrue(source.contains("binder?.isBinderAlive != true"))
    }

    @Test fun everyCommandRequiresLivePermissionAndShellUid() {
        val source = read(session)
        assertTrue(source.contains("Shizuku.pingBinder()"))
        assertTrue(source.contains("Shizuku.getUid() == Process.SHELL_UID"))
        assertTrue(source.contains("Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED"))
        assertTrue(source.contains("bridge.execute(\"id -u\", 3_000L)"))
        assertTrue(source.contains("bridge.close()"))
        assertTrue(source.contains("throw SecurityException("))
    }

    @Test fun transportHasNoSocketFallbackAndQuitRemovesListeners() {
        val wire = read(transport)
        val source = read(session)
        assertTrue(wire.contains("core.execVerified(command, timeoutMs)"))
        assertTrue(wire.contains("ShizukuHostAdapter.current()"))
        for (obsolete in listOf("AbsAdbConnectionManager", "LibAdbWire", "exec:sh", "Socket(")) {
            assertFalse(obsolete, wire.contains(obsolete))
            assertFalse(obsolete, source.contains(obsolete))
        }
        assertTrue(source.contains("Shizuku.removeBinderDeadListener(binderDied)"))
        assertTrue(source.contains("Shizuku.removeBinderReceivedListener(binderReceived)"))
        assertTrue(source.contains("scope.cancel()"))
    }
}
