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
import dev.n2k02.mcplogger.R
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
        const val LOCAL_START = "dev.n2k02.mcplogger.LOCAL_MCP_START"
        const val TUNNEL_RESTART = "dev.n2k02.mcplogger.TUNNEL_RESTART"
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
    private var tunnelJob: Job? = null
    private var foreground = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android may restart a sticky foreground service without the original Intent.
        when (intent?.action ?: LOCAL_START) {
            QUIT -> {
                stopCapture()
                QuickMcpTunnel.stop()
                tunnelJob?.cancel()
                tunnelJob = null
                LocalMcpServer.stop()
                getSharedPreferences("mcp_logger", MODE_PRIVATE).edit()
                    .putBoolean("manual_quit", true).apply()
                scope.launch { AdbCoordinator.quit() }
                MonitorState.quit()
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
                stopSelf()
                return START_NOT_STICKY
            }
            STOP -> {
                stopCapture()
                // MCP stays available; only Quit shuts down the server.
                return START_STICKY
            }
            TUNNEL_RESTART -> {
                ensureForeground()
                LocalMcpServer.start(applicationContext)
                QuickMcpTunnel.stop()
                tunnelJob?.cancel()
                tunnelJob = scope.launch {
                    delay(1200)
                    QuickMcpTunnel.start(applicationContext)
                }
            }
            LOCAL_START, START -> {
                if (intent?.action == LOCAL_START) getSharedPreferences("mcp_logger", MODE_PRIVATE)
                    .edit().putBoolean("manual_quit", false).apply()
                ensureForeground()
                val started = LocalMcpServer.start(applicationContext)
                if (started && tunnelJob?.isActive != true) {
                    tunnelJob = scope.launch { QuickMcpTunnel.start(applicationContext) }
                }
                if (intent?.action == START) startCapture()
            }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        if (foreground) return
        val channelId = "logger_monitor"
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(channelId, "MCP ADB local server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_app_notification)
            .setContentTitle("MCP ADB Logger")
            .setContentText("Read-only MCP server active on this phone")
            .setContentIntent(open)
            .setOngoing(true).build()
        startForeground(2026, notification)
        foreground = true
    }

    private fun startCapture() {
        if (job?.isActive == true) return
        getSharedPreferences("mcp_logger", MODE_PRIVATE).edit()
            .putBoolean("manual_quit", false).apply()
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
                    // Query package identities through the authorized shell rather than
                    // requesting broad Android QUERY_ALL_PACKAGES app visibility.
                    val packagesOutput = AdbTransport.exec("pm list packages -3 -U", timeoutMs = 12000)
                        .getOrNull().orEmpty()
                    if (packagesOutput.isNotBlank()) {
                        val known = PackageListParser.parse(packagesOutput)
                        MonitorState.packageUids.value = known
                        MonitorState.packages.value = known.keys.sorted().take(500)
                    }
                    val packageName = MonitorState.selectedPackage.value
                    val uid = MonitorState.packageUids.value[packageName]
                    val output = AdbTransport.exec("logcat -b main -b system -b crash -v uid -v epoch -d -t 300",
                        timeoutMs = 12000).getOrThrow()
                    val newEntries = buffer.add(output.lineSequence().mapNotNull(LogFoxParser::parse).toList(),
                        uid, packageName.ifBlank { null })
                    if (newEntries.isNotEmpty()) {
                        MonitorState.logs.value = buffer.snapshot(0, 1000)
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
    private fun stopCapture() {
        MonitorState.active.value = false
        job?.cancel()
        job = null
    }
    override fun onDestroy() {
        stopCapture()
        QuickMcpTunnel.stop()
        tunnelJob?.cancel()
        LocalMcpServer.stop()
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }
}
