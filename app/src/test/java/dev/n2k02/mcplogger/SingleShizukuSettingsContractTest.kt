package dev.n2k02.mcplogger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One ADB Settings submenu, provided exclusively by a Shizuku Binder server. */
class SingleShizukuSettingsContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun onlyOneAdbSettingsEntryAndNoLegacyConnectionSubmenu() {
        val nav = source("app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt")
        val rows = nav.substringAfter("Page.SETTINGS ->").substringBefore("Page.SHIZUKU ->")
        assertEquals(1, Regex("SettingsRow\\(\"ADB\"").findAll(rows).count())
        assertFalse(rows.contains("SettingsRow(\"ADB connection\""))
        assertFalse(rows.contains("SettingsRow(\"Shizuku\""))
        assertFalse(rows.contains("Pair, authorize and recover shell UID 2000"))
        assertFalse(rows.contains("Optional privileged diagnostics and ADB fallback"))
        assertTrue(rows.contains("navigateTo(Page.SHIZUKU)"))
        assertTrue(nav.contains("ShizukuSettingsPage()"))
        val navigation = source("app/src/main/java/dev/n2k02/mcplogger/LoggerNavigation.kt")
        assertTrue(navigation.contains("SHIZUKU(\"ADB\")"))
        assertFalse(navigation.contains("ADB_CONNECTION"))
        assertFalse(File("app/src/main/java/dev/n2k02/mcplogger/AdbHomeSetupCard.kt").exists())
    }

    @Test fun adbPageUsesVerifiedShizukuPermissionAndRechecksWhenReturningFromLocalAdb() {
        val page = source("app/src/main/java/dev/n2k02/mcplogger/ShizukuSettingsPage.kt")
        assertTrue(page.contains("Shizuku.pingBinder()"))
        assertTrue(page.contains("Shizuku.checkSelfPermission()"))
        assertTrue(page.contains("Shizuku.getUid()"))
        assertTrue(page.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(page.contains("Shizuku.requestPermission(2902)"))
        assertTrue(page.contains("Get LocalADB · GitHub Releases"))
        assertFalse(page.contains("Enter pairing code"))
        assertFalse(page.contains("Wireless debugging"))
        assertFalse(page.contains("switchToTcpip("))
    }
}
