package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test

class LogFoxParserTest {
    @Test fun parsesEpochUidAndError() {
        val event = LogFoxParser.parse("1720012345.123 10234 1412 1413 E AndroidRuntime: FATAL EXCEPTION")
        assertNotNull(event)
        assertEquals("10234", event!!.uid)
        assertEquals(1720012345123L, event.epochMillis)
        assertEquals("AndroidRuntime", event.tag)
        assertTrue(LogFoxParser.isCrash(event))
    }
    @Test fun ignoresMalformedOrTruncatedLogcat() {
        assertNull(LogFoxParser.parse("no timestamp"))
        assertNull(LogFoxParser.parse(""))
        assertNull(LogFoxParser.parse("17200.broken 2 3 4 E Tag: invalid"))
    }
    @Test fun anrsCountAsCrashSignals() {
        assertTrue(LogFoxParser.isCrash(LogEvent(1, 1, "1", "2", "E", "ActivityManager", "ANR in app", "x")))
    }
}
