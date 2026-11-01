package dev.n2k02.mcplogger.core

import java.util.Calendar

/** Cache retention follows the device's local calendar day, including DST. */
internal object LogDayPolicy {
    fun dayStart(atMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = atMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun nextMidnight(atMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = atMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    fun recentToday(events: List<LogEvent>, nowMs: Long): List<LogEvent> {
        val from = dayStart(nowMs)
        val until = nextMidnight(nowMs)
        // A modest device-clock skew must not import entries from tomorrow.
        return events.filter {
            it.epochMillis >= from && it.epochMillis < until &&
                it.epochMillis <= nowMs + 60_000L
        }
    }
}
