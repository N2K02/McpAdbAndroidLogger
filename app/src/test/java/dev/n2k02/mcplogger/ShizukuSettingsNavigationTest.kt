package dev.n2k02.mcplogger

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ShizukuSettingsNavigationTest {
    private fun source(path: String): String {
        val app = listOf(File("app"), File("../app")).first { it.isDirectory }
        return app.resolve("src/main/java/dev/n2k02/mcplogger/" + path).readText()
    }

    @Test fun shizukuSettingsAreAppSpecificAndSharedAdbPageIsUntouched() {
        val ui = source("LoggerUi.kt")
        val adb = ui.substringAfter("private fun AdbPage(").substringBefore("private fun ShizukuPage(")
        assertFalse(adb.contains("ShizukuAvailability"))
        assertFalse(adb.contains("Authorize Shizuku"))
        assertTrue(adb.contains("AdbConnectionSettingsPage("))
        assertTrue(ui.contains("SettingsRow(\"Shizuku\""))
        assertTrue(ui.contains("Page.SHIZUKU -> ShizukuPage("))
        assertTrue(source("LoggerNavigation.kt").contains("SHIZUKU(\"Shizuku\")"))
    }

    @Test fun backendDefaultsOnButNeverGrantsPermissionSilently() {
        val prefs = source("adb/ShizukuPreferences.kt")
        val backend = source("shizuku/ShizukuReadBackend.kt")
        val ui = source("LoggerUi.kt")
        assertTrue(prefs.contains(".getBoolean(KEY, true)"))
        assertTrue(prefs.contains("fun setEnabled("))
        assertTrue(backend.contains("ShizukuPreferences.enabled(context)"))
        assertTrue(backend.contains("ShizukuPreferences.setEnabled(context, enabled)"))
        assertTrue(backend.contains("if (enabled) bindIfAuthorized(context)"))
        assertTrue(ui.contains("Switch(checked = enabled"))
        assertTrue(ui.contains("ShizukuReadBackend"))
        assertFalse(backend.contains("requestPermission("))
    }

    @Test fun backNavigationKeepsShizukuUnderSettings() {
        val navigation = LoggerNavigation(Page.SETTINGS)
            .open(Page.SHIZUKU)
        assertEquals(Page.SHIZUKU, navigation.page)
        assertEquals(Page.SETTINGS, navigation.parent)
        assertTrue(navigation.settingsSelected)
        assertEquals(Page.SETTINGS, navigation.back().page)
    }
}
