package dev.n2k02.mcplogger

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CompactLogcatScreenTest {
    private fun screen(): String {
        val path = "app/src/main/java/dev/n2k02/mcplogger/LogcatScreen.kt"
        return listOf(File(path), File("../$path")).first { it.isFile }.readText()
    }

    @Test fun logListGetsSpaceWithCollapsedFiltersAndCompactRows() {
        val screen = screen()
        assertTrue(screen.contains("var advanced by remember { mutableStateOf(false) }"))
        assertTrue(screen.contains("var levelExpanded by remember { mutableStateOf(false) }"))
        assertTrue(screen.contains("var actionMenu by remember { mutableStateOf(false) }"))
        assertTrue(screen.contains("if (advanced) {"))
        assertTrue(screen.contains("FilterChip(selected = filter.packageName.isNotBlank()"))
        assertTrue(screen.contains("FilterChip(selected = filter.minLevel != \"V\""))
        assertTrue(screen.contains("OutlinedTextField(\n                value = filter.search"))
        assertTrue(screen.contains("onClick = { expanded = !expanded }"))
        assertTrue(screen.contains("maxLines = if (expanded) Int.MAX_VALUE else 2"))
        assertTrue(screen.contains("HorizontalDivider(thickness = 0.5.dp"))
        assertFalse(screen.contains("Text(\"Android Logcat snapshots\", style"))
    }

    @Test fun secondaryActionsAreInMoreMenuAndDangerousClearNeedsConfirmation() {
        val screen = screen()
        assertTrue(screen.contains("DropdownMenu(expanded = actionMenu"))
        assertTrue(screen.contains("Text(\"Copy visible logs\")"))
        assertTrue(screen.contains("Text(\"Export visible logs\")"))
        assertTrue(screen.contains("Text(\"Reset filters\")"))
        assertTrue(screen.contains("Text(\"Clear captured logs\")"))
        assertTrue(screen.contains("Text(\"Clear device Logcat (all buffers)\")"))
        assertTrue(screen.contains("deviceClearConfirmation = true"))
        assertTrue(screen.contains("clearConfirmation = true"))
        assertTrue(screen.contains("LogMonitorService.clear()"))
        assertTrue(screen.contains("AppPickerDialog(apps, filter.packageName"))
        assertTrue(screen.contains("LogViewPolicy.select(logs, filter, uids"))
    }
}
