package dev.n2k02.mcplogger.core

import kotlinx.coroutines.flow.MutableStateFlow

data class LogEvent(
    val id: Long,
    val epochMillis: Long,
    val uid: String,
    val pid: String,
    val level: String,
    val tag: String,
    val message: String,
    val raw: String,
    val tid: String = ""
)
data class LogCaptureStats(
    val dayStartMs: Long = 0L,
    val linesScanned: Long = 0,
    val uniqueCaptured: Long = 0,
    val scanCycles: Long = 0,
    val lastSuccessAtMillis: Long = 0,
    val lastBatchCaptured: Int = 0,
    val readFailures: Long = 0,
)

enum class LogReadOrigin { MANUAL, MCP }

/** Metadata for the last successful request. Event IDs are references into the bounded cache. */
data class RecentLogRequest(
    val origin: LogReadOrigin,
    val tool: String,
    val atMillis: Long,
    val filter: LogViewFilter,
    val entryIds: Set<Long> = emptySet()
) {
    fun isMcpSelection(current: LogViewFilter) =
        origin == LogReadOrigin.MCP && current == filter
}


object MonitorState {
    val active = MutableStateFlow(false)
    val logReadOrigin = MutableStateFlow<LogReadOrigin?>(null)
    /** Tracks overlapping MCP tool calls so Home badges reflect any in-flight request. */
    val mcpToolsInFlight = MutableStateFlow<Set<String>>(emptySet())
    private val mcpToolCounts = mutableMapOf<String, Int>()
    @Synchronized fun beginMcpTool(name: String) {
        if (name.isBlank()) return
        mcpToolCounts[name] = (mcpToolCounts[name] ?: 0) + 1
        mcpToolsInFlight.value = mcpToolCounts.keys.toSet()
    }
    @Synchronized fun endMcpTool(name: String) {
        val count = mcpToolCounts[name] ?: return
        if (count == 1) mcpToolCounts.remove(name) else mcpToolCounts[name] = count - 1
        mcpToolsInFlight.value = mcpToolCounts.keys.toSet()
    }
    @Synchronized fun clearMcpTools() {
        mcpToolCounts.clear()
        mcpToolsInFlight.value = emptySet()
    }
    val recentLogRequest = MutableStateFlow<RecentLogRequest?>(null)
    val adb = MutableStateFlow("Not connected")
    val gateway = MutableStateFlow("Disconnected")
    val localMcp = MutableStateFlow("Stopped")
    val publicMcpUrl = MutableStateFlow("")
    /** True only while LogMonitorService is promoted to Android foreground. */
    val foregroundServiceActive = MutableStateFlow(false)
    val tunnel = MutableStateFlow("Off")
    val selectedPackage = MutableStateFlow("")
    val logFilter = MutableStateFlow(LogViewFilter())
    val captureStats = MutableStateFlow(LogCaptureStats())
    val logs = MutableStateFlow<List<LogEvent>>(emptyList())
    val clearingDeviceLogcat = MutableStateFlow(false)
    val logcatMaintenanceStatus = MutableStateFlow("")
    val errors = MutableStateFlow<List<String>>(emptyList())
    val packages = MutableStateFlow<List<String>>(emptyList())
    val packageUids = MutableStateFlow<Map<String, String>>(emptyMap())
    fun error(message: String) {
        errors.value = (errors.value + message.take(300)).takeLast(100)
    }
    fun quit() {
        active.value = false
        logReadOrigin.value = null
        clearMcpTools()
        recentLogRequest.value = null
        adb.value = "Stopped"
        gateway.value = "Stopped"
    }
}
