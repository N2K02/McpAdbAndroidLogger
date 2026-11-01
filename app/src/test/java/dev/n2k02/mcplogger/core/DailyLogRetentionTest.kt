package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.io.File

class DailyLogRetentionTest {
    private fun event(id: Long, at: Long): LogEvent =
        LogEvent(id, at, "1000", "123", "I", "test", "message", "raw-" + id)

    @Test fun keepsOnlyTheCurrentCalendarDayAtMidnight() {
        val sample = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 1, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        val next = LogDayPolicy.nextMidnight(sample)
        assertTrue(next > sample)
        assertEquals(next, LogDayPolicy.dayStart(next))
        val inputs = listOf(event(1, sample), event(2, next), event(3, next + 20_000))
        assertEquals(listOf(1L), LogDayPolicy.recentToday(inputs, sample).map { it.id })
        assertEquals(listOf(2L, 3L), LogDayPolicy.recentToday(inputs, next + 40_000).map { it.id })
    }

    @Test fun ignoresFutureTimestampsAndPrunesPreviousDaysEvenIfLogdStillHasThem() {
        val now = System.currentTimeMillis()
        val start = LogDayPolicy.dayStart(now)
        val events = listOf(event(1, start - 1), event(2, start),
            event(3, now), event(4, now + 60_001L))
        assertEquals(listOf(2L, 3L), LogDayPolicy.recentToday(events, now).map { it.id })
    }

    @Test fun localBufferResetDoesNotReuseCursorIdentifiers() {
        val buffer = LogBuffer()
        val a = buffer.add(listOf(event(0, 10_000)), null, null).single()
        buffer.clear(resetDedup = true)
        val b = buffer.add(listOf(event(0, 20_000)), null, null).single()
        assertTrue(b.id > a.id)
        assertEquals(1, buffer.snapshot().size)
    }

    @Test fun serviceSchedulesOnlyOneMidnightPruneAndNeverAutomaticallyClearsAndroidLogs() {
        val path = "app/src/main/java/dev/n2k02/mcplogger/core/LogMonitorService.kt"
        val code = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(code.contains("if (dailyPruneJob?.isActive == true) return"))
        assertTrue(code.contains("LogDayPolicy.nextMidnight(now)"))
        assertTrue(code.contains("LogDayPolicy.recentToday("))
        assertTrue(code.contains("buffer.clear(resetDedup = true)"))
        assertFalse(code.substringAfter("private fun ensureDailyPruning()")
            .substringBefore("private fun ensureRelayHeartbeat()")
            .contains("AdbTransport.exec("))
    }
}