package dev.n2k02.mcplogger

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Tests for core UI redesign requirements, avoiding accidental regressions. */
class HomeAndLogcatUiContractTest {
    private fun code(filename: String): String {
        val path = "app/src/main/java/dev/n2k02/mcplogger/" + filename
        return listOf(File(path), File("../" + path)).first { it.isFile }.readText()
    }

    @Test fun logsIsTopLevelAndNotInSettingsOrHome() {
        val app = code("LoggerUi.kt")
        assertTrue(app.contains("LoggerNavigation(page, subpageParent).logsSelected"))
        assertTrue(app.contains("onClick = { navigateTo(Page.LOGS) }"))
        val settings = app.substringAfter("Page.SETTINGS -> Column(")
            .substringBefore("Page.ADB -> AdbPage")
        assertFalse(settings.contains("SettingsRow(\"Logs\""))
        val home = code("HomeDashboard.kt")
        assertFalse(home.contains("Recent logs"))
        assertFalse(home.contains("Open Logs"))
        assertFalse(home.contains("Filter application"))
        assertTrue(home.contains("MonitorState.captureStats.collectAsState()"))
    }

    @Test fun filtersAndSafeExportAreAvailableInLogcat() {
        val src = code("LogcatScreen.kt")
        assertTrue(src.contains("AppPickerDialog(apps, filter.packageName"))
        assertTrue(src.contains("getApplicationIcon(packageName)"))
        assertTrue(src.contains("getApplicationLabel(info)"))
        assertTrue(src.contains("LogViewPolicy.select(logs, filter, uids"))
        assertTrue(src.contains("Crashes / ANRs"))
        assertTrue(src.contains("Reset filters"))
        assertTrue(src.contains("LogMonitorService.clear()"))
        assertTrue(src.contains("CreateDocument(\"text/plain\")"))
        assertTrue(src.contains("LogExportFormatter.export(snapshot)"))
        assertTrue(src.contains("copy(\"Filtered Android Logcat\", filtered)"))
        assertTrue(src.contains("Copy log entry"))
    }

    @Test fun deviceLogcatClearIsConfirmedAndHomeStatsCanResetSeparately() {
        val logcat = code("LogcatScreen.kt")
        assertTrue(logcat.contains("Clear all Android Logcat buffers?"))
        assertTrue(logcat.contains("LogMonitorService.CLEAR_DEVICE_LOGCAT"))
        assertTrue(logcat.contains("adbVerified && !deviceLogcatClearing"))
        assertTrue(logcat.contains("MCP audit history is not deleted"))
        assertTrue(logcat.contains("LogMonitorService.clear()"))
        val home = code("HomeDashboard.kt")
        assertTrue(home.contains("Reset Home statistics"))
        assertTrue(home.contains("LogMonitorService.resetHomeStatistics()"))
        val service = code("core/LogMonitorService.kt")
        assertTrue(service.contains("DeviceLogcatReset.COMMAND"))
        assertTrue(service.contains("AdbCoordinator.verified.value"))
        assertTrue(service.contains("getOrThrow().trim() == \"2000\""))
        assertTrue(service.contains("resetGate.applyIfCurrent(readGeneration)"))
        assertTrue(service.contains("buffer.clear(resetDedup = true)"))
        assertFalse(home.contains("MonitorState.adb.value ="))
        assertFalse(home.contains("MonitorState.publicMcpUrl.value ="))
    }

    @Test fun captureIsIndependentOfSelectedAppAndTracksMetrics() {
        val service = code("core/LogMonitorService.kt")
        assertTrue(service.contains("buffer.add(parsed, null, null)"))
        assertTrue(service.contains("linesScanned = previous.linesScanned + parsed.size"))
        assertTrue(service.contains("uniqueCaptured = previous.uniqueCaptured + newEntries.size"))
        val manifest = listOf(File("app/src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        assertFalse(manifest.contains("android.permission.QUERY_ALL_PACKAGES"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
    }
    @Test fun tunnelStatusIconTracksLiveConnectionAndTone() {
        val app = code("LoggerUi.kt")
        assertTrue(app.contains("MonitorState.tunnel.collectAsState()"))
        assertTrue(app.contains("MCP tunnel status: $tunnel"))
        assertTrue(app.contains("Icons.Rounded.Cloud"))
        assertTrue(app.contains("navigateTo(Page.BRIDGE)"))
        assertEquals(Tone.GOOD, tunnelTone("Public MCP connected"))
        assertEquals(Tone.BUSY, tunnelTone("Tunnel is reconnecting"))
        assertEquals(Tone.BAD, tunnelTone("Stable relay unavailable"))
        assertEquals(Tone.BAD, tunnelTone("Off"))
    }

    @Test fun logcatTabsSwipeAndTapLikeHome() {
        val home = code("HomeDashboard.kt")
        val logcat = code("LogcatScreen.kt")
        for (token in listOf("rememberPagerState(", "HorizontalPager(state = pagerState",
                "snapshotFlow { pagerState.settledPage }", "animateScrollToPage(",
                "TabRow(selectedTabIndex = pagerState.currentPage)")) {
            assertTrue("Home missing " + token, home.contains(token))
            assertTrue("Logcat missing " + token, logcat.contains(token))
        }
        assertTrue(logcat.contains("pageCount = { LogcatTab.entries.size }"))
        assertTrue(logcat.contains("when (LogcatTab.entries[page])"))
        assertTrue(logcat.contains("onClick = { selectLogcatTab(destination) }"))
        assertTrue(logcat.contains("selectLogcatTab(LogcatTab.FILTERS)"))
        assertTrue(logcat.contains("selectLogcatTab(LogcatTab.STORAGE)"))
        assertTrue(logcat.contains("selectLogcatTab(LogcatTab.LOGS)"))
        assertTrue(logcat.contains("BackHandler(enabled = HomeTabHistory.canGoBack(tabHistory))"))
        assertTrue(logcat.contains("HomeTabHistory.visit(tabHistory, page"))
        assertTrue(logcat.contains("scope.launch { pagerState.scrollToPage(prior.last()) }"))
        assertTrue(logcat.contains("LazyColumn(Modifier.fillMaxWidth().weight(1f)"))
        assertTrue(logcat.contains("LogcatTab.LOGS -> {"))
        assertTrue(logcat.contains("Column(Modifier.fillMaxSize())"))
        assertTrue(logcat.contains("MonitorState.logFilter.collectAsState()"))
        assertFalse(logcat.contains("when (tab)"))
    }

}
