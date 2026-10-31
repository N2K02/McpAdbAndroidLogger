package dev.n2k02.mcplogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression tests for system Back, gesture Back, toolbar Back and tab switching. */
class LoggerNavigationTest {
    private val settingsPages = listOf(
        Page.ADB, Page.BRIDGE, Page.LOGS, Page.DIAGNOSTICS, Page.UPDATES
    )

    @Test fun everySettingsSubpageReturnsToSettingsAndThenHome() {
        for (destination in settingsPages) {
            val settings = LoggerNavigation().open(Page.SETTINGS)
            val subpage = settings.open(destination)
            assertEquals("Wrong parent for $destination", Page.SETTINGS, subpage.parent)
            assertTrue("Back gesture must be handled for $destination", subpage.handlesBack)
            assertTrue("Settings tab must remain selected in $destination", subpage.settingsSelected)
            assertEquals(settings, subpage.back())
            assertEquals(LoggerNavigation(), subpage.back().back())
        }
    }

    @Test fun homeShortcutsReturnHomeNotSettings() {
        for (destination in listOf(Page.ADB, Page.LOGS)) {
            val home = LoggerNavigation()
            val subpage = home.open(destination)
            assertEquals(Page.HOME, subpage.parent)
            assertTrue(subpage.homeSelected)
            assertEquals(home, subpage.back())
        }
    }

    @Test fun backAtHomeIsNotConsumedAndAllowsAndroidToExit() {
        val home = LoggerNavigation()
        assertFalse(home.handlesBack)
        assertEquals(home, home.back())
    }

    @Test fun settingsBackReturnsHomeInsteadOfFinishingApp() {
        val settings = LoggerNavigation().open(Page.SETTINGS)
        assertTrue(settings.handlesBack)
        assertEquals(Page.HOME, settings.back().page)
    }

    @Test fun switchingBottomTabsClearsStaleSubpageParent() {
        val homeLogs = LoggerNavigation().open(Page.LOGS)
        val settings = homeLogs.open(Page.SETTINGS)
        assertEquals(Page.SETTINGS, settings.open(Page.ADB).back().page)

        val home = settings.open(Page.HOME)
        assertEquals(Page.HOME, home.open(Page.LOGS).back().page)
    }

    @Test fun changingSubpagesPreservesTheirOriginalParent() {
        val homeLogs = LoggerNavigation().open(Page.LOGS)
        assertEquals(Page.HOME, homeLogs.open(Page.ADB).back().page)

        val settingsLogs = LoggerNavigation().open(Page.SETTINGS).open(Page.LOGS)
        assertEquals(Page.SETTINGS, settingsLogs.open(Page.UPDATES).back().page)
    }

    @Test fun appWiresSystemGestureAndToolbarToSameBackAction() {
        val relative = "app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt"
        val file = listOf(File(relative), File("../$relative")).firstOrNull { it.isFile }
            ?: error("Missing $relative")
        val source = file.readText()
        assertTrue(source.contains("BackHandler(enabled = page != Page.HOME) { navigateBack() }"))
        assertTrue(source.contains("IconButton(onClick = { navigateBack() })"))
        assertTrue(source.contains("rememberSaveable { mutableStateOf(Page.HOME) }"))
        assertTrue(source.contains("rememberSaveable { mutableStateOf(Page.SETTINGS) }"))
    }
}
