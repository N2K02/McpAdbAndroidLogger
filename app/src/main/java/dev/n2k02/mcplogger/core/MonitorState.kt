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
    val raw: String
)
data class LogCaptureStats(
    val linesScanned: Long = 0,
    val uniqueCaptured: Long = 0,
    val scanCycles: Long = 0,
    val lastSuccessAtMillis: Long = 0,
    val lastBatchCaptured: Int = 0,
    val readFailures: Long = 0,
)

object MonitorState {
    val active = MutableStateFlow(false)
    val adb = MutableStateFlow("Not connected")
    val gateway = MutableStateFlow("Disconnected")
    val localMcp = MutableStateFlow("Stopped")
    val publicMcpUrl = MutableStateFlow("")
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
        adb.value = "Stopped"
        gateway.value = "Stopped"
    }
}
