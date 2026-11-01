package dev.n2k02.mcplogger

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Portrait-first sub-tabs keep filters and maintenance off the Logs viewport. */
class CompactLogcatScreenTest {
    private fun code(file: String): String {
        val path = "app/src/main/java/dev/n2k02/mcplogger/" + file
        return listOf(File(path), File("../$path")).first { it.isFile }.readText()
    }

    @Test fun logsHasPortraitFirstTabsAndMaximumSpaceForMessages() {
        val screen = code("LogcatScreen.kt")
        assertTrue(screen.contains("private enum class LogcatTab(val title: String)"))
        assertTrue(screen.contains("LOGS(\"Logs\"), FILTERS(\"Filters\"), STORAGE(\"Storage\")"))
        assertTrue(screen.contains("TabRow(selectedTabIndex = tab.ordinal)"))
        assertTrue(screen.contains("var tab by rememberSaveable { mutableStateOf(LogcatTab.LOGS) }"))
        assertTrue(screen.contains("LogcatTab.LOGS -> {"))
        assertTrue(screen.contains("LogcatTab.FILTERS -> {"))
        assertTrue(screen.contains("LogcatTab.STORAGE -> {"))
        assertTrue(screen.contains("placeholder = { Text(\"Search logs, tags or PID\") }"))
        assertTrue(screen.contains("items(recentFirst, key = { it.id })"))
        assertTrue(screen.contains("remember(filtered) { filtered.asReversed() }"))
        assertTrue(screen.contains("maxLines = if (expanded) Int.MAX_VALUE else 2"))
        assertTrue(screen.contains("HorizontalDivider(thickness = 0.5.dp"))
        assertFalse(screen.contains("Text(\"Android Logcat snapshots\", style"))
    }

    @Test fun filtersAndStorageDoNotClutterLogViewport() {
        val screen = code("LogcatScreen.kt")
        val logs = screen.substringAfter("LogcatTab.LOGS -> {").substringBefore("LogcatTab.FILTERS -> {")
        val filters = screen.substringAfter("LogcatTab.FILTERS -> {").substringBefore("LogcatTab.STORAGE -> {")
        val storage = screen.substringAfter("LogcatTab.STORAGE -> {").substringBefore("private fun LogcatEntryCard")
        assertFalse(logs.contains("OutlinedTextField(value = filter.tag"))
        assertFalse(logs.contains("Clear device Logcat (all buffers)"))
        assertTrue(filters.contains("Crashes / ANRs"))
        assertTrue(filters.contains("AppIcon(filter.packageName)"))
        assertTrue(filters.contains("filter.minLevel"))
        assertTrue(filters.contains("filter.windowMinutes"))
        assertTrue(filters.contains("filter.tag"))
        assertTrue(filters.contains("filter.pid"))
        assertTrue(storage.contains("Clear captured logs"))
        assertTrue(storage.contains("Clear device Logcat (all buffers)"))
        assertTrue(storage.contains("Export visible logs"))
        assertTrue(storage.contains("Daily reset enabled"))
        assertTrue(screen.contains("DropdownMenu(expanded = actionMenu"))
        assertTrue(screen.contains("deviceClearConfirmation = true"))
        assertTrue(screen.contains("LogMonitorService.clear()"))
    }

    @Test fun homeDailyCardUsesTodayOnlyCountersAndSharedMidnightPolicy() {
        val home = code("HomeDashboard.kt")
        val state = code("core/MonitorState.kt")
        val service = code("core/LogMonitorService.kt")
        assertTrue(home.contains("Logcat (Today)"))
        assertTrue(home.contains("Daily reset enabled"))
        assertTrue(home.contains("todayShown.toString()"))
        assertTrue(home.contains("currentStats.uniqueCaptured"))
        assertTrue(home.contains("currentStats.scanCycles"))
        assertTrue(home.contains("LogDayPolicy.dayStart(System.currentTimeMillis())"))
        assertTrue(state.contains("val dayStartMs: Long = 0L"))
        assertTrue(service.contains("LogCaptureStats(dayStartMs = today)"))
        assertTrue(service.contains("LogDayPolicy.nextMidnight(now)"))
        assertTrue(service.contains("buffer.clear(resetDedup = true)"))
    }

    @Test fun performanceSnapshotsAreBoundedAndDoNotRedrawLogRowsRepeatedly() {
        val perf = code("core/PerformanceDiagnostics.kt")
        val logs = code("LogcatScreen.kt")
        assertTrue(perf.contains("withContext(Dispatchers.Default)"))
        assertTrue(perf.contains("if (samples.size >= 240) samples.removeFirst()"))
        assertTrue(perf.contains("val recent = samples.toList()"))
        assertTrue(logs.contains("remember(event.message) { SensitiveLogRedactor.redact(event.message) }"))
    }
}
