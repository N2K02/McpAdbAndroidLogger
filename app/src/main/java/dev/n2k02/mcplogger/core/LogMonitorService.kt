package dev.n2k02.mcplogger.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Build
import dev.n2k02.mcplogger.MainActivity
import dev.n2k02.mcplogger.adb.AdbCoordinator
import dev.n2k02.mcplogger.adb.AdbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * LogFox-compatible logcat capture. Read-only shell commands; collection is
 * stopped immediately by Quit. Background capture requires foreground notification.
 */
class LogMonitorService : Service() {
    companion object {
        const val START = "dev.n2k02.mcplogger.START"
        const val STOP = "dev.n2k02.mcplogger.STOP"
        const val QUIT = "dev.n2k02.mcplogger.QUIT"
        private val buffer = LogBuffer()
        fun snapshot(after: Long = 0, limit: Int = 300) = buffer.snapshot(after, limit)
        fun clear() {
            buffer.clear()
            MonitorState.logs.value = emptyList()
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP, QUIT -> {
                stop()
                if (intent.action == QUIT) {
                    getSharedPreferences("mcp_logger", MODE_PRIVATE).edit()
                        .putBoolean("manual_quit", true).apply()
                    scope.launch { AdbCoordinator.quit() }
                    MonitorState.quit()
                }
                stopSelf()
                return START_NOT_STICKY
            }
            START -> start()
        }
        return START_NOT_STICKY
    }
    private fun start() {
        if (job?.isActive == true) return
        getSharedPreferences("mcp_logger", MODE_PRIVATE).edit()
            .putBoolean("manual_quit", false).apply()
        val channelId = "logger_monitor"
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(channelId, "Live Android log monitoring", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("MCP ADB Logger")
            .setContentText("Reading Logcat with authorized shell access")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(2026, notification)
        MonitorState.active.value = true
        job = scope.launch {
            AdbCoordinator.discover(applicationContext)
            var attempt = 0
            while (isActive && MonitorState.active.value) {
                try {
                    if (!AdbTransport.isConnected) {
                        AdbCoordinator.connect(applicationContext).getOrThrow()
                        attempt = 0
                    }
                    val packageName = MonitorState.selectedPackage.value
                    val uid = runCatching {
                        if (packageName.isBlank()) null else packageManager
                            .getApplicationInfo(packageName, 0).uid.toString()
                    }.getOrNull()
                    val output = AdbTransport.exec("logcat -b main -b system -b crash -v uid -v epoch -d -t 300",
                        timeoutMs = 12000).getOrThrow()
                    val newEntries = buffer.add(output.lineSequence().mapNotNull(LogFoxParser::parse).toList(),
                        uid, packageName.ifBlank { null })
                    if (newEntries.isNotEmpty()) {
                        MonitorState.logs.value = buffer.snapshot(0, 1000)
                    }
                    if (BridgeClient.configured(applicationContext))
                        BridgeClient.upload(applicationContext, newEntries)
                    val packagesOutput = AdbTransport.exec("pm list packages -3", timeoutMs = 12000)
                        .getOrNull().orEmpty()
                    if (packagesOutput.isNotBlank()) {
                        MonitorState.packages.value = packagesOutput.lineSequence()
                            .filter { it.startsWith("package:") }.map { it.removePrefix("package:") }
                            .sorted().take(500).toList()
                    }
                    attempt = 0
                    delay(2500L)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    MonitorState.error("Logcat: " + (error.message ?: "capture error"))
                    attempt = (attempt + 1).coerceAtMost(6)
                    delay((1000L shl attempt).coerceAtMost(60000L))
                }
            }
        }
    }
    private fun stop() {
        MonitorState.active.value = false
        job?.cancel()
        job = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() {
        stop()
        scope.cancel()
        super.onDestroy()
    }
}
