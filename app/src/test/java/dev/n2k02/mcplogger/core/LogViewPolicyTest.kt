package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test

class LogViewPolicyTest {
    private fun line(id: Long, uid: String = "10001", level: String = "I",
                     tag: String = "Sample", msg: String = "hello", pid: String = "123") =
        LogEvent(id, 600000, uid, pid, level, tag, msg, uid + " " + tag + ": " + msg)

    private val logs = listOf(
        line(1, "10001", "D", "Audio", "starting"),
        line(2, "10002", "E", "AndroidRuntime", "FATAL EXCEPTION"),
        line(3, "10001", "W", "Audio", "some warning"),
        line(4, "10002", "F", "Crash", "Fatal signal")
    )
    private val uids = mapOf("app.a" to "10001", "app.b" to "10002")

    @Test fun appFilterUsesUidAndNeverLeaksUnknownUid() {
        assertEquals(listOf(1L,3L), LogViewPolicy.select(logs,
            LogViewFilter(packageName="app.a"), uids, 600000).map { it.id })
        assertTrue(LogViewPolicy.select(logs,
            LogViewFilter(packageName="unavailable"), uids, 600000).isEmpty())
        assertEquals(4, LogViewPolicy.select(logs, LogViewFilter(), uids, 600000).size)
        val mentioning = line(5, uid="10002", msg="app.a")
        assertTrue(LogViewPolicy.select(listOf(mentioning),
            LogViewFilter(packageName="app.a"), uids, 600000).isEmpty())
    }

    @Test fun severityAndCrashesAreCombinable() {
        assertEquals(listOf(2L,4L), LogViewPolicy.select(logs,
            LogViewFilter(minLevel="E"), uids, 600000).map { it.id })
        assertEquals(listOf(2L,4L), LogViewPolicy.select(logs,
            LogViewFilter(crashesOnly=true), uids, 600000).map { it.id })
        assertEquals(listOf(2L), LogViewPolicy.select(logs,
            LogViewFilter(crashesOnly=true, search="EXCEPTION"), uids, 600000).map { it.id })
    }

    @Test fun tagPidSearchAndTimeCombine() {
        assertEquals(listOf(3L), LogViewPolicy.select(logs,
            LogViewFilter(tag="aUd", pid="12", search="WARNING", windowMinutes=5),
            uids, 600000).map { it.id })
        assertTrue(LogViewPolicy.select(logs, LogViewFilter(windowMinutes=5),
            uids, 1000000).isEmpty())
    }

    @Test fun resetClearsAllFilters() {
        assertEquals(LogViewFilter(), LogViewPolicy.reset())
    }

    @Test fun exportedTextIsRedacted() {
        val export = LogExportFormatter.export(listOf(line(5,
            msg="password=hello token=world mary@example.com")))
        assertFalse(export.contains("password=hello"))
        assertFalse(export.contains("token=world"))
        assertFalse(export.contains("mary@example.com"))
        assertTrue(export.contains("[REDACTED]"))
    }
}
