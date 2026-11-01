package dev.n2k02.mcplogger.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** MCP must never start a permanent Logcat stream or poll without a request. */
class OnDemandLogcatTest {
    private fun call(name: String, args: String = "{}") = JSONObject(
        """{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"$name","arguments":$args}}"""
    )

    private fun event(id: Long, text: String = "ready") =
        LogEvent(id, 1_700_000_000_000, "10123", "234", "I", "App", text, text)

    private fun result(reply: JSONObject): org.json.JSONArray {
        assertFalse(reply.has("error"))
        return org.json.JSONArray(reply.getJSONObject("result")
            .getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun logcatReadsHappenOnlyForLogTools() {
        val reads = AtomicInteger(0)
        val apps = AtomicInteger(0)
        val reader: () -> List<LogEvent> = { reads.incrementAndGet(); listOf(event(10)) }
        val appReader: () -> List<String> = { apps.incrementAndGet(); listOf("com.example") }

        LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"initialize","id":1}"""), logsReader = reader, appsReader = appReader)
        LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":2}"""), logsReader = reader, appsReader = appReader)
        LocalMcpProtocol.execute(call("get_device_status"), logsReader = reader, appsReader = appReader)
        assertEquals(0, reads.get())
        assertEquals(0, apps.get())

        assertEquals(1, result(LocalMcpProtocol.execute(call("get_live_logs"),
            logsReader = reader, appsReader = appReader)!!).length())
        assertEquals(1, reads.get())
        for (tool in listOf("search_logs", "get_crashes", "get_anrs")) {
            val args = if (tool == "search_logs") """{"query":"ready"}""" else "{}"
            LocalMcpProtocol.execute(call(tool, args), logsReader = reader, appsReader = appReader)
        }
        assertEquals(4, reads.get())
        assertEquals(0, apps.get())
        result(LocalMcpProtocol.execute(call("list_installed_apps"),
            logsReader = reader, appsReader = appReader)!!)
        assertEquals(4, reads.get())
        assertEquals(1, apps.get())
    }

    @Test fun everyReadUsesFreshSnapshotAndPreservesCursor() {
        var calls = 0
        val reader: () -> List<LogEvent> = {
            calls++
            if (calls == 1) listOf(event(10), event(11))
            else listOf(event(10), event(11), event(12, "new entry"))
        }
        val first = result(LocalMcpProtocol.execute(call("get_live_logs"),
            logsReader = reader)!!)
        assertEquals(2, first.length())
        val after = first.getJSONObject(1).getLong("cursor")
        val next = result(LocalMcpProtocol.execute(call("get_live_logs",
            """{"after_cursor":$after}"""), logsReader = reader)!!)
        assertEquals(1, next.length())
        assertEquals("new entry", next.getJSONObject(0).getString("message"))
        assertEquals(2, calls)
    }

    @Test fun commandFailureIsNotReplacedByStaleCachedData() {
        val reply = LocalMcpProtocol.execute(call("get_live_logs"),
            logsReader = { error("ADB is offline") })!!
        assertEquals(-32603, reply.getJSONObject("error").getInt("code"))
        assertFalse(reply.toString().contains("ADB is offline"))
    }

    @Test fun packageInventoryIsCachedForOrdinaryLogReadsButExplicitRefreshIsFresh() {
        val p = PackageRefreshPolicy
        assertTrue(p.shouldRefresh(1_000L, -1L, false, false))
        assertFalse(p.shouldRefresh(60_000L, 1_000L, true, false))
        assertTrue(p.shouldRefresh(121_000L, 1_000L, true, false))
        assertTrue(p.shouldRefresh(60_000L, 1_000L, true, true))
        assertTrue(p.shouldRefresh(500L, 1_000L, true, false))
        val path = "app/src/main/java/dev/n2k02/mcplogger/core/LogMonitorService.kt"
        val service = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(service.contains("refreshInstalledApps(force = false)"))
        assertTrue(service.contains("packageListLock.withLock"))
    }

    @Test fun noPollLoopAndAppRefreshUsesSingleBoundedDump() {
        val p = "app/src/main/java/dev/n2k02/mcplogger/core/LogMonitorService.kt"
        val service = listOf(File(p), File("../$p")).first { it.isFile }.readText()
        assertFalse(service.contains("while (isActive && MonitorState.active.value)"))
        assertFalse(service.contains("delay(2500L)"))
        assertTrue(service.contains("suspend fun readOnDemand(origin: LogReadOrigin"))
        assertTrue(service.contains("readLock.withLock"))
        assertTrue(service.contains("readOnDemand(LogReadOrigin.MANUAL)"))
        assertTrue(service.contains("scheduleIdleLogCleanup()"))
        assertTrue(service.contains("cancelIdleLogCleanup()"))
        assertTrue(service.contains("MonitorState.logReadOrigin.value = null"))
        assertTrue(service.contains("logcat -b main -b system -b crash -v uid -v epoch -d -t 2000"))
        assertTrue(service.contains("MonitorState.active.value = false"))
        val serverPath = "app/src/main/java/dev/n2k02/mcplogger/core/LocalMcpServer.kt"
        val server = listOf(File(serverPath), File("../$serverPath"))
            .first { it.isFile }.readText()
        assertTrue(server.contains("logsReader = {"))
        assertTrue(server.contains("LogMonitorService.readOnDemand()"))
    }
}
