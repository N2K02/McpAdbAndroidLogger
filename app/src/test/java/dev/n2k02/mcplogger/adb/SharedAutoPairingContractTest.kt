package dev.n2k02.mcplogger.adb

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** The app requests Shizuku permission; no wireless-debugging pairing reader exists. */
class SharedAutoPairingContractTest {
    private val root: File
        get() = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { it.resolve("app/build.gradle.kts").isFile }
    private fun source(path: String) = root.resolve(path).readText()

    @Test fun noPairingAccessibilityOrWirelessTransport() {
        assertFalse(root.resolve("app/src/main/java/dev/n2k02/mcplogger/adb/PairingAccessibilityService.kt").exists())
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertFalse(manifest.contains("PairingAccessibilityService"))
        assertTrue(manifest.contains("moe.shizuku.manager.permission.API_V23"))
        assertTrue(manifest.contains("rikka.shizuku.ShizukuProvider"))
        val transport = source("app/src/main/java/dev/n2k02/mcplogger/adb/AdbTransport.kt")
        assertTrue(transport.contains("ShizukuHostAdapter.current()"))
        assertFalse(transport.contains("LibAdbWire"))
    }

    @Test fun settingsUsesShizukuPermissionAndVerifiedShellUid() {
        val nav = source("app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt")
        val settings = source("app/src/main/java/dev/n2k02/mcplogger/ShizukuSettingsPage.kt")
        val shell = source("app/src/main/java/dev/n2k02/mcplogger/shizuku/ShizukuSession.kt")
        assertTrue(nav.contains("Page.SHIZUKU ->"))
        assertTrue(nav.contains("SettingsRow(\"ADB\""))
        assertTrue(settings.contains("Shizuku.checkSelfPermission()"))
        assertTrue(shell.contains("Shizuku.checkSelfPermission()"))
        assertTrue(shell.contains("Process.SHELL_UID"))
        assertTrue(shell.contains("id -u"))
    }
}
