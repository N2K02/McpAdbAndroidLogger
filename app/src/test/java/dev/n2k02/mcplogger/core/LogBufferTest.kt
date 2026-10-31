package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test

class LogBufferTest {
    private fun event(n: Int, uid: String = "10001") =
        LogEvent(0, n.toLong(), uid, "123", "D", "TAG", "message", "raw-" + n)

    @Test fun deduplicatesAndMaintainsBoundedCursor() {
        val b = LogBuffer(2)
        assertEquals(2, b.add(listOf(event(1), event(2), event(2)), null, null).size)
        b.add(listOf(event(3)), null, null)
        assertEquals(listOf(2L, 3L), b.snapshot().map { it.id })
        assertEquals(listOf(3L), b.snapshot(2).map { it.id })
        b.clear()
        assertTrue(b.snapshot().isEmpty())
    }
    @Test fun appUidFilterKeepsOnlyTargetEvents() {
        val b = LogBuffer()
        b.add(listOf(event(1), event(2, "20002")), "10001", "some.app")
        assertEquals(1, b.snapshot().size)
    }
    @Test fun redactionDoesNotExposeTokensOrEmails() {
        val input = "Authorization: Bearer987 token=secretvalue john@example.com 4111111111111111"
        val filtered = SensitiveLogRedactor.redact(input)
        assertFalse(filtered.contains("secretvalue"))
        assertFalse(filtered.contains("john@example.com"))
        assertFalse(filtered.contains("4111111111111111"))
    }
}
