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
object MonitorState {
    val active = MutableStateFlow(false)
    val adb = MutableStateFlow("Not connected")
    val gateway = MutableStateFlow("Disconnected")
    val selectedPackage = MutableStateFlow("")
    val logs = MutableStateFlow<List<LogEvent>>(emptyList())
    val errors = MutableStateFlow<List<String>>(emptyList())
    val packages = MutableStateFlow<List<String>>(emptyList())
    fun error(message: String) {
        errors.value = (errors.value + message.take(300)).takeLast(100)
    }
    fun quit() {
        active.value = false
        adb.value = "Stopped"
        gateway.value = "Stopped"
    }
}
