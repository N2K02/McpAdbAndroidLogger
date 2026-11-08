package dev.n2k02.mcplogger

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Swipe and tap history must navigate within Home before the Activity can exit. */
class HomeTabNavigationTest {
    @Test fun tabsRecordSettledVisitsAndBackReturnsToPreviousTab() {
        var visited = listOf(0) // Overview
        visited = HomeTabHistory.visit(visited, 1, 3) // Performance
        visited = HomeTabHistory.visit(visited, 2, 3) // Connection
        assertEquals(listOf(0, 1, 2), visited)
        assertTrue(HomeTabHistory.canGoBack(visited))
        visited = HomeTabHistory.back(visited)
        assertEquals(listOf(0, 1), visited)
        visited = HomeTabHistory.back(visited)
        assertEquals(listOf(0), visited)
        assertFalse(HomeTabHistory.canGoBack(visited))
        assertEquals(listOf(0), HomeTabHistory.back(visited))
    }

    @Test fun eachSwipeCountsAsOneVisitAndRepeatedSettledPageIsIgnored() {
        var visits = listOf(0)
        visits = HomeTabHistory.visit(visits, 1, 3)
        visits = HomeTabHistory.visit(visits, 1, 3)
        visits = HomeTabHistory.visit(visits, 2, 3)
        assertEquals(listOf(0, 1, 2), visits)
        visits = HomeTabHistory.back(visits)
        // Pager settling after Back should NOT reinsert the page we just popped.
        visits = HomeTabHistory.visit(visits, 1, 3)
        assertEquals(listOf(0, 1), visits)
        // Returning to Overview by tap is a real visit, not an Activity exit.
        visits = HomeTabHistory.visit(visits, 0, 3)
        assertEquals(listOf(0, 1, 0), visits)
        assertEquals(listOf(0, 1), HomeTabHistory.back(visits))
    }

    @Test fun historyIsBoundedAndNeverAcceptsOutOfRangePages() {
        var visits = listOf(0)
        for (i in 0..180) visits = HomeTabHistory.visit(visits, 1 + (i % 2), 3)
        assertTrue(visits.size <= 24)
        assertEquals(visits, HomeTabHistory.visit(visits, -1, 3))
        assertEquals(visits, HomeTabHistory.visit(visits, 4, 3))
        assertEquals(listOf(0), HomeTabHistory.back(emptyList()))
    }

    @Test fun homeHasOneManualDashboardWithoutPerformanceOrConnectionTabs() {
        val homePath = "app/src/main/java/dev/n2k02/mcplogger/HomeDashboard.kt"
        val home = listOf(File(homePath), File("../$homePath"))
            .first { it.isFile }.readText()
        assertTrue(home.contains("Manual Logcat (Today)"))
        assertTrue(home.contains("MCP activity"))
        assertTrue(home.contains("MCP Logcat reads"))
        assertFalse(home.contains("HomeTab.PERFORMANCE"))
        assertFalse(home.contains("HomeTab.CONNECTION"))
        assertFalse(home.contains("HorizontalPager(state = pagerState"))
        val navPath = "app/src/main/java/dev/n2k02/mcplogger/LoggerUi.kt"
        val nav = listOf(File(navPath), File("../$navPath")).first { it.isFile }.readText()
        assertTrue(nav.contains("Page.HOME -> HomePage("))
    }
}
