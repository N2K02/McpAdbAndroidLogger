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
        assertTrue(screen.contains("TabRow(selectedTabIndex = pagerState.currentPage)"))
        assertTrue(screen.contains("HorizontalPager(state = pagerState"))
        assertTrue(screen.contains("pageCount = { LogcatTab.entries.size }"))
        assertTrue(screen.contains("when (LogcatTab.entries[page])"))
        assertTrue(screen.contains("snapshotFlow { pagerState.settledPage }"))
        assertTrue(screen.contains("onClick = { selectLogcatTab(destination) }"))
        assertTrue(screen.contains("BackHandler(enabled = HomeTabHistory.canGoBack(tabHistory))"))
        assertTrue(screen.contains("LogcatTab.LOGS -> {"))
        // A pager page needs one vertical layout root. Without it, sibling
        // composables overlap and the search/filters/log list become invisible.
        val logsPage = screen.substringAfter("LogcatTab.LOGS -> {")
            .substringBefore("LogcatTab.FILTERS -> {")
        assertTrue(logsPage.contains("Column(Modifier.fillMaxSize()) {"))
        val status = logsPage.indexOf("Text(filtered.size.toString()")
        val search = logsPage.indexOf("BasicTextField(value = filter.search")
        val logRows = logsPage.indexOf("LazyColumn(Modifier.fillMaxWidth().weight(1f)")
        assertTrue("Logcat status must appear first", status >= 0)
        assertTrue("Search box must appear below status", search > status)
        assertTrue("Scrollable log list must appear below search", logRows > search)
        assertTrue(logsPage.contains("Search logs, tags or PID"))
        assertFalse(logsPage.contains("FilterChip("))
        assertTrue(logsPage.contains("BasicTextField(value = filter.search"))
        assertTrue(screen.contains("LazyColumn(Modifier.fillMaxWidth().weight(1f)"))
        assertTrue(screen.contains("Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp)"))
        assertTrue(screen.contains("LogcatTab.FILTERS -> {"))
        assertTrue(screen.contains("LogcatTab.STORAGE -> {"))
        assertTrue(screen.contains("Text(\"Search logs, tags or PID\""))
        assertTrue(screen.contains("items(recentFirst, key = { it.id })"))
        assertTrue(screen.contains("remember(filtered) { filtered.asReversed() }"))
        assertTrue(screen.contains("var expanded by remember(event.id)"))
        assertTrue(screen.contains("Expand log details"))
        assertTrue(screen.contains("Collapse log details"))
        assertTrue(screen.contains("SelectionContainer"))
        assertTrue(screen.contains("LogSeverity.title(event.level)"))
        assertTrue(screen.contains("Text(safeMessage"))
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
        assertTrue(filters.contains("LogViewPolicy.levelSummary(filter)"))
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

    @Test fun logsToolbarKeepsIdleStatusAndSearchButtonSeparated() {
        val screen = code("LogcatScreen.kt")
        val toolbar = screen.substringAfter(
            "Text(filtered.size.toString() + \" shown · \" + logs.size + \" buffered\"")
            .substringBefore("Box {\n                            IconButton(onClick = { actionMenu = true })")
        assertTrue(toolbar.contains("horizontalArrangement = Arrangement.spacedBy(8.dp)") ||
            screen.contains("horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                        Text(filtered.size"))
        assertTrue(toolbar.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(toolbar.contains("\"● Idle\""))
        assertTrue(toolbar.contains("Text(\"Search\")"))
    }

    @Test fun homeDailyCardCountsOnlyManualSearches() {
        val home = code("HomeDashboard.kt")
        val state = code("core/MonitorState.kt")
        val service = code("core/LogMonitorService.kt")
        assertTrue(home.contains("Manual Logcat (Today)"))
        assertTrue(home.contains("Manual searches"))
        assertTrue(home.contains("MCP Logcat reads"))
        assertTrue(home.contains("currentStats.uniqueCaptured"))
        assertTrue(home.contains("currentStats.scanCycles"))
        assertTrue(home.contains("LogDayPolicy.dayStart(System.currentTimeMillis())"))
        assertFalse(home.contains("HomeTab.PERFORMANCE"))
        assertFalse(home.contains("HomeTab.CONNECTION"))
        assertTrue(state.contains("val dayStartMs: Long = 0L"))
        assertTrue(service.contains("LogCaptureStats(dayStartMs = today)"))
        assertTrue(service.contains("LogDayPolicy.nextMidnight(now)"))
        assertTrue(service.contains("mcpBuffer.add(parsed, null, null)"))
    }

    @Test fun performanceSnapshotsAreBoundedAndDoNotRedrawLogRowsRepeatedly() {
        val perf = code("core/PerformanceDiagnostics.kt")
        val logs = code("LogcatScreen.kt")
        assertTrue(perf.contains("withContext(Dispatchers.Default)"))
        assertTrue(perf.contains("if (samples.size >= 240) samples.removeFirst()"))
        assertTrue(perf.contains("val recent = samples.toList()"))
        assertTrue(logs.contains("remember(event.message) { SensitiveLogRedactor.redact(event.message) }"))
    }

    @Test fun logsSearchOnlyAndFilterControlsOnlyInFilters() {
        val screen = code("LogcatScreen.kt")
        val logs = screen.substringAfter("LogcatTab.LOGS -> {")
            .substringBefore("LogcatTab.FILTERS -> {")
        val filters = screen.substringAfter("LogcatTab.FILTERS -> {")
            .substringBefore("LogcatTab.STORAGE -> {")
        assertTrue(logs.contains("BasicTextField(value = filter.search"))
        assertTrue(logs.contains("Search logs, tags or PID"))
        assertFalse(logs.contains("FilterChip("))
        assertFalse(logs.contains("showPicker = true"))
        assertFalse(logs.contains("LevelMultiSelectMenu("))
        assertTrue(filters.contains("OutlinedCard(onClick = { showPicker = true }"))
        assertTrue(filters.contains("LevelMultiSelectMenu(levelExpanded"))
        assertTrue(filters.contains("filter.crashesOnly"))
        assertTrue(filters.contains("filter.windowMinutes"))
        assertTrue(filters.contains("OutlinedTextField(value = filter.tag"))
        assertTrue(filters.contains("OutlinedTextField(value = filter.pid"))
        assertTrue(filters.contains("RoundedCornerShape(18.dp)"))
        assertTrue(screen.contains("activeFilterCount = LogViewPolicy.activeFilterCount(filter)"))
        assertTrue(screen.contains("destination == LogcatTab.FILTERS && activeFilterCount > 0"))
        assertTrue(screen.contains("Badge { Text(activeFilterCount.toString()) }"))
        assertTrue(screen.contains("DropdownMenu(expanded = actionMenu"))
        assertTrue(screen.contains("update(LogViewPolicy.reset())"))
    }

    @Test fun storageUsesModernRoundedMaterialThreeComponents() {
        val storage = code("LogcatScreen.kt").substringAfter("LogcatTab.STORAGE -> {")
            .substringBefore("private fun LogcatEntryCard")
        assertTrue(storage.contains("shape = RoundedCornerShape(20.dp)"))
        assertTrue(storage.contains("shape = RoundedCornerShape(18.dp)"))
        for (label in listOf("Copy visible logs", "Export visible logs",
                "Clear captured logs", "Clear device Logcat (all buffers)"))
            assertTrue(storage.contains(label))
    }}
