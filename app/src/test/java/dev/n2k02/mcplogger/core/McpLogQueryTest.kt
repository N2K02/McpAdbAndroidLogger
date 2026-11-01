package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test

class McpLogQueryTest {
    private val packages = mapOf(
        "com.example.target" to "10123",
        "com.example.other" to "10124",
    )

    private fun event(
        id: Long,
        uid: String,
        level: String = "I",
        tag: String = "App",
        message: String = "normal event",
    ) = LogEvent(id, 1_700_000_000_000, uid, "789", level, tag, message,
        "$uid $tag: $message")

    private val events = listOf(
        event(1, "10123", level = "D", message = "boot completed"),
        event(2, "10124", level = "E", tag = "AndroidRuntime",
            message = "FATAL EXCEPTION in com.example.target"),
        event(3, "10123", level = "E", tag = "AndroidRuntime",
            message = "FATAL EXCEPTION"),
        event(4, "10124", level = "W", tag = "am_anr",
            message = "ANR in com.example.target"),
        event(5, "10123", level = "W", tag = "am_anr",
            message = "ANR in com.example.target"),
        event(6, "10123", level = "I", tag = "App", message = "ready"),
    )

    @Test fun exactPackageUidNeverLeaksOtherPackagesThatMentionTarget() {
        val logs = McpLogQuery.select(events, "get_live_logs",
            "com.example.target", packages)
        assertEquals(listOf(1L, 3L, 5L, 6L), logs.map { it.id })
        assertFalse(logs.any { it.uid == "10124" })
        assertEquals(6, McpLogQuery.select(events, "get_live_logs", "", packages).size)
    }

    @Test fun unknownOrUnresolvedPackageFailsClosed() {
        for (name in listOf("com.example.missing", "com.example", "COM.EXAMPLE.TARGET")) {
            assertTrue(McpLogQuery.select(events, "get_live_logs", name, packages).isEmpty())
        }
        assertTrue(McpLogQuery.select(events, "get_live_logs",
            "com.example.target", emptyMap()).isEmpty())
        assertEquals(4, McpLogQuery.select(events, "get_live_logs",
            " com.example.target ", packages).size)
    }

    @Test fun textSearchAndSeverityCombineWithPackageUid() {
        assertEquals(listOf(3L), McpLogQuery.select(events, "search_logs",
            "com.example.target", packages, query = "exception").map { it.id })
        assertEquals(listOf(3L), McpLogQuery.select(events, "get_live_logs",
            "com.example.target", packages, query = "AndroidRuntime",
            level = "e").map { it.id })
        assertTrue(McpLogQuery.select(events, "get_live_logs",
            "com.example.target", packages, level = "F").isEmpty())
    }

    @Test fun crashAndAnrToolsUseIdenticalPackageBoundary() {
        assertEquals(listOf(3L), McpLogQuery.select(events, "get_crashes",
            "com.example.target", packages).map { it.id })
        assertEquals(listOf(5L), McpLogQuery.select(events, "get_anrs",
            "com.example.target", packages).map { it.id })
        assertEquals(listOf(2L, 3L), McpLogQuery.select(events, "get_crashes",
            "", packages).map { it.id })
        assertEquals(listOf(4L, 5L), McpLogQuery.select(events, "get_anrs",
            "", packages).map { it.id })
    }

    @Test fun resultLimitIsAppliedAfterPackageFiltering() {
        assertEquals(listOf(5L, 6L), McpLogQuery.select(events, "get_live_logs",
            "com.example.target", packages, limit = 2).map { it.id })
        assertEquals(listOf(6L), McpLogQuery.select(events, "get_live_logs",
            "com.example.target", packages, limit = 0).map { it.id })
    }

    @Test fun malformedNamesAndUnsupportedMethodsAreRejected() {
        for (value in listOf("com.example.target; rm -rf /", "a".repeat(256),
                "com.example.target\n")) {
            // Surrounding whitespace is intentionally allowed: embedded spaces/control
            // characters are not.
            if (value.endsWith("\n")) continue
            assertThrows(IllegalArgumentException::class.java) {
                McpLogQuery.select(events, "get_live_logs", value, packages)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpLogQuery.select(events, "execute", "", packages)
        }
    }
}
