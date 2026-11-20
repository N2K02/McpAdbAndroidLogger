package dev.n2k02.mcplogger.adb

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Assert Shizuku session prefs never restore wireless-debugging or pairing settings. */
class AdbRecoverySettingsTest {
    private fun source(): String = listOf(
        File("app/src/main/java/dev/n2k02/mcplogger/adb/AdbRecoverySettings.kt"),
        File("../app/src/main/java/dev/n2k02/mcplogger/adb/AdbRecoverySettings.kt")
    ).first { it.isFile }.readText()

    @Test fun manualQuitIsStickyAndForegroundRestartIsExplicit() {
        val s = source()
        assertTrue(s.contains("getBoolean(\"manual_quit\", false)"))
        assertTrue(s.contains("putBoolean(\"manual_quit\", stopped).commit()"))
        assertTrue(s.contains("setManuallyQuit(context, false)"))
        assertTrue(s.contains("!manuallyQuit(context)"))
    }
    @Test fun authorizationHistoryCannotEnableShellAccess() {
        val s = source()
        assertTrue(s.contains("getLong(\"core_verified_at\", 0L)"))
        assertFalse(s.contains("adb_pair_verified"))
        assertFalse(s.contains("adb_last_tls_port"))
        assertFalse(s.contains("adb_wifi_enabled"))
        assertFalse(s.contains("restoreWirelessAfterReboot"))
        assertFalse(s.contains("automatic_pairing_code"))
    }
}
