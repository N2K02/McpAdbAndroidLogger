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
        val rows = nav.substringAfter("Page.SETTINGS -> Column(").substringBefore("Page.SHIZUKU -> Column(")
        assertEquals(1, rows.split("SettingsRow(\"ADB\"").size - 1)
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

    @Test fun adbPageUsesTheSameVerifiedShizukuSourceAsHome() {
        val page = source("app/src/main/java/dev/n2k02/mcplogger/ShizukuSettingsPage.kt")
        val home = source("app/src/main/java/dev/n2k02/mcplogger/HomeDashboard.kt")
        val status = source("app/src/main/java/dev/n2k02/mcplogger/ShizukuAccessStatus.kt")
        assertTrue(page.contains("readShizukuAccessStatus()"))
        assertTrue(home.contains("rememberShizukuAccessStatus()"))
        assertTrue(home.contains("val verified = shizukuStatus.shellReady"))
        assertFalse(home.contains("adb.startsWith("))
        assertTrue(status.contains("Shizuku.pingBinder()"))
        assertTrue(status.contains("Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED"))
        assertTrue(status.contains("Shizuku.getUid()"))
        assertTrue(status.contains("shellUid == Process.SHELL_UID"))
        assertTrue(status.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(status.contains("delay(2_000L)"))
        assertTrue(status.contains("lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)"))
        assertTrue(page.contains("rememberShizukuAccessStatus()"))
        assertTrue(page.contains("remember(sharedStatus, revision)"))
        assertFalse(page.contains("Shizuku.addBinderReceivedListenerSticky("))
        assertTrue(status.contains("Shizuku.OnBinderDeadListener"))
        assertTrue(status.contains("Shizuku.OnRequestPermissionResultListener"))
        assertTrue(page.contains("Shizuku.requestPermission(2902)"))
        assertTrue(page.contains("Get LocalADB · GitHub Releases"))
        assertFalse(page.contains("Enter pairing code"))
        assertFalse(page.contains("Wireless debugging"))
        assertFalse(page.contains("switchToTcpip("))
    }
}
