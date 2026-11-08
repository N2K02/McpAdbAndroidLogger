package dev.n2k02.mcplogger

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LoggerNavigationTest {
    private val settingsPages = listOf(Page.ADB, Page.BRIDGE, Page.UPDATES)

    @Test fun settingsDetailsBackToSettingsThenHome() {
        for (destination in settingsPages) {
            val settings = LoggerNavigation().open(Page.SETTINGS)
            val subpage = settings.open(destination)
            assertEquals(Page.SETTINGS, subpage.parent)
            assertTrue(subpage.settingsSelected)
            assertTrue(subpage.handlesBack)
            assertEquals(settings, subpage.back())
            assertEquals(LoggerNavigation(), subpage.back().back())
        }
    }

    @Test fun logsIsIndependentTabWithSystemBackToHome() {
        val logs = LoggerNavigation().open(Page.LOGS)
        assertTrue(logs.logsSelected)
        assertFalse(logs.homeSelected)
        assertFalse(logs.settingsSelected)
        assertTrue(logs.handlesBack)
        assertEquals(LoggerNavigation(), logs.back())
        assertEquals(Page.LOGS, LoggerNavigation().open(Page.SETTINGS).open(Page.LOGS).page)
    }

    @Test fun auditsIsIndependentSecondRootTab() {
        val audits = LoggerNavigation().open(Page.MCP_AUDITS)
        assertTrue(audits.auditsSelected)
        assertFalse(audits.homeSelected)
        assertFalse(audits.settingsSelected)
        assertEquals(LoggerNavigation(), audits.back())
        assertEquals(Page.MCP_AUDITS,
            LoggerNavigation().open(Page.SETTINGS).open(Page.MCP_AUDITS).page)
    }

    @Test fun homeShortcutReturnsHome() {
        val adb = LoggerNavigation().open(Page.ADB)
        assertTrue(adb.homeSelected)
        assertEquals(LoggerNavigation(), adb.back())
    }

    @Test fun homeLeavesBackToAndroid() {
        assertFalse(LoggerNavigation().handlesBack)
        assertEquals(LoggerNavigation(), LoggerNavigation().back())
    }

    @Test fun switchingTabsDoesNotInheritParent() {
        val logs = LoggerNavigation().open(Page.SETTINGS).open(Page.ADB).open(Page.LOGS)
        assertTrue(logs.logsSelected)
        val settings = logs.open(Page.SETTINGS)
        assertEquals(settings, settings.open(Page.BRIDGE).back())
    }

    @Test fun diagnosticsSubmenuRemoved() {
        assertTrue(Page.values().none { it.name == "DIAGNOSTICS" })
        val path = "app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt"
        val ui = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertFalse(ui.contains("DiagnosticsPage"))
        assertFalse(ui.contains("SettingsRow(\"Diagnostics\""))
    }

    @Test fun serverButtonTogglesStartAndStop() {
        val path = "app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt"
        val ui = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(ui.contains("if (serverRunning) \"Stop server\" else \"Start server\""))
        assertTrue(ui.contains("setAction(LogMonitorService.LOCAL_STOP)"))
        val svcPath = "app/src/main/java/dev/n2k02/mcplogger/core/LogMonitorService.kt"
        val svc = listOf(File(svcPath), File("../$svcPath")).first { it.isFile }.readText()
        val stopBlock = svc.substringAfter("LOCAL_STOP -> {").substringBefore("TUNNEL_RESTART -> {")
        assertTrue(stopBlock.contains("QuickMcpTunnel.stop()"))
        assertTrue(stopBlock.contains("LocalMcpServer.stop()"))
    }

    @Test fun aiClientButtonsOpenConnectorPages() {
        val path = "app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt"
        val ui = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(ui.contains("\"https://claude.ai/new#customize/connectors/yours\""))
        assertTrue(ui.contains("\"https://chatgpt.com/plugins\""))
    }

    @Test fun toolbarAndGestureShareHandler() {
        val path = "app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt"
        val ui = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(ui.contains("BackHandler(enabled = page != Page.HOME) { navigateBack() }"))
        assertTrue(ui.contains("IconButton(onClick = { navigateBack() })"))
        assertTrue(ui.contains("rememberSaveable { mutableStateOf(Page.HOME) }"))
        assertTrue(ui.contains("rememberSaveable { mutableStateOf(Page.SETTINGS) }"))
    }
}
