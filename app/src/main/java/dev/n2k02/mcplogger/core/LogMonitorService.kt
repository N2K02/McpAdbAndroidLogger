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
import dev.n2k02.mcplogger.adb.AdbRecoveryLifecycle
import dev.n2k02.mcplogger.adb.AdbRecoverySettings
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
        /** New public URL and new client key; everything else keeps running. */
        const val TUNNEL_ROTATE = "dev.n2k02.mcplogger.TUNNEL_ROTATE"
        const val STOP = "dev.n2k02.mcplogger.STOP"
        const val CLEAR_DEVICE_LOGCAT = "dev.n2k02.mcplogger.CLEAR_DEVICE_LOGCAT"
        const val LOCAL_STOP = "dev.n2k02.mcplogger.LOCAL_MCP_STOP"
        const val QUIT = "dev.n2k02.mcplogger.QUIT"
        private val buffer = LogBuffer()
        private val resetGate = LogcatResetGate()
        fun snapshot(after: Long = 0, limit: Int = 300) = buffer.snapshot(after, limit)
        /** Clear app-only cached Logcat records; retain Android system buffers. */
        fun clear() = resetGate.reset {
            buffer.clear()
            MonitorState.logs.value = emptyList()
        }
        /** Home metrics only; ADB and tunnel status always remain truthful. */
        fun resetHomeStatistics() = resetGate.reset {
            MonitorState.captureStats.value = LogCaptureStats()
            MonitorState.errors.value = emptyList()
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var tunnelJob: Job? = null
    private var relayHeartbeatJob: Job? = null
    private var foreground = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android may restart a sticky foreground service without the original Intent.
        when (intent?.action ?: LOCAL_START) {
            CLEAR_DEVICE_LOGCAT -> {
                // Local user action only, never callable from remote MCP tools.
                if (!resetGate.beginDeviceClear {
                        MonitorState.clearingDeviceLogcat.value = true
                        MonitorState.logcatMaintenanceStatus.value = "Clearing Android Logcat..."
                    }) return START_STICKY
                scope.launch {
                    var cleared = false
                    var message = "Android Logcat clearing was interrupted"
                    try {
                        ensureForeground()
                        check(AdbCoordinator.verified.value) {
                            "Verify ADB shell UID 2000 first"
                        }
                        check(AdbTransport.exec("id -u", timeoutMs = 5000)
                            .getOrThrow().trim() == "2000") { "ADB shell UID is not 2000" }
                        val result = AdbTransport.exec(DeviceLogcatReset.COMMAND,
                            timeoutMs = 12000).getOrThrow()
                        check(DeviceLogcatReset.succeeded(result)) {
                            "The device rejected logcat -c"
                        }
                        cleared = true
                        message = "All Android Logcat buffers cleared"
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        message = "Device Logcat clear failed: " +
                            (error.message ?: error.javaClass.simpleName)
                    } finally {
                        resetGate.finishDeviceClear {
                            if (cleared) {
                                buffer.clear(resetDedup = true)
                                MonitorState.logs.value = emptyList()
                                MonitorState.captureStats.value = LogCaptureStats()
                                MonitorState.errors.value = emptyList()
                            }
                            MonitorState.clearingDeviceLogcat.value = false
                            MonitorState.logcatMaintenanceStatus.value = message
                        }
                    }
                }
                return START_STICKY
            }
            QUIT -> {
                stopCapture()
                QuickMcpTunnel.stop()
                tunnelJob?.cancel()
                tunnelJob = null
                relayHeartbeatJob?.cancel()
                relayHeartbeatJob = null
                LocalMcpServer.stop()
                getSharedPreferences("mcp_logger", MODE_PRIVATE).edit()
                    .putBoolean("manual_quit", true).apply()
                AdbRecoverySettings.setManuallyQuit(applicationContext, true)
                AdbRecoveryLifecycle.shutdown(applicationContext)
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
            LOCAL_STOP -> {
                // User-requested server stop: tunnel and local MCP go down and stay
                // down (no auto-start on reopen or boot) until Start server is pressed.
                QuickMcpTunnel.stop()
                tunnelJob?.cancel()
                tunnelJob = null
                relayHeartbeatJob?.cancel()
                relayHeartbeatJob = null
                LocalMcpServer.stop()
                getSharedPreferences("mcp_logger", MODE_PRIVATE).edit()
                    .putBoolean("mcp_server_enabled", false).apply()
                if (!MonitorState.active.value) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foreground = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            TUNNEL_RESTART, TUNNEL_ROTATE -> {
                getSharedPreferences("mcp_logger", MODE_PRIVATE).edit()
                    .putBoolean("mcp_server_enabled", true).apply()
                ensureForeground()
                if (intent?.action == TUNNEL_ROTATE) {
                    BridgeClient.saveClientToken(applicationContext, McpConnectionSetup.newSecret())
                    QuickMcpTunnel.forget(applicationContext)
                }
                LocalMcpServer.start(applicationContext)
                QuickMcpTunnel.stop()
                tunnelJob?.cancel()
                tunnelJob = scope.launch {
                    delay(1200)
                    QuickMcpTunnel.start(applicationContext)
                }
                ensureRelayHeartbeat()
            }
            LOCAL_START, START -> {
                // Persistent MCP connection must survive leaving the Activity.
                // A manual server stop is distinct from the app-wide Quit.
                val prefs = getSharedPreferences("mcp_logger", MODE_PRIVATE)
                if (intent?.action == LOCAL_START)
                    prefs.edit().putBoolean("mcp_server_enabled", true).apply()
                val shouldHost = prefs.getBoolean("mcp_server_enabled", true) &&
                    !prefs.getBoolean("manual_quit", false)
                if (shouldHost) {
                    ensureForeground()
                    val started = LocalMcpServer.start(applicationContext)
                    if (started && tunnelJob?.isActive != true) {
                        tunnelJob = scope.launch { QuickMcpTunnel.start(applicationContext) }
                        ensureRelayHeartbeat()
                    }
                }
                if (intent?.action == START) {
                    ensureForeground()
                    startCapture()
                }
                if (!shouldHost && intent?.action != START && !MonitorState.active.value) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    /**
     * Workers expire the phone route after four minutes for safety. Renew it
     * every minute only while cloudflared confirms a live edge connection.
     * If Android stops this foreground service, the route eventually expires.
     */
    private fun ensureRelayHeartbeat() {
        if (relayHeartbeatJob?.isActive == true) return
        relayHeartbeatJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                if (StableMcpRelay.publicMcpUrl(applicationContext) != null) {
                    val renewed = QuickMcpTunnel.refreshStableRelay(applicationContext)
                    if (!renewed && MonitorState.tunnel.value == "Public MCP connected")
                        MonitorState.tunnel.value = "Stable relay unavailable"
                    if (renewed && MonitorState.tunnel.value == "Stable relay unavailable")
                        MonitorState.tunnel.value = "Public MCP connected"
                }
            }
        }
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
            .setContentText("MCP tunnel active · tap to manage connection")
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
                    // Filtering is UI-only so switching apps cannot discard captured logs.
                    val readGeneration = resetGate.generation()
                    val output = AdbTransport.exec("logcat -b main -b system -b crash -v uid -v epoch -d -t 300",
                        timeoutMs = 12000).getOrThrow()
                    val parsed = output.lineSequence().mapNotNull(LogFoxParser::parse).toList()
                    resetGate.applyIfCurrent(readGeneration) {
                        val newEntries = buffer.add(parsed, null, null)
                        MonitorState.logs.value = buffer.snapshot(0, 2000)
                        val previous = MonitorState.captureStats.value
                        MonitorState.captureStats.value = previous.copy(
                            linesScanned = previous.linesScanned + parsed.size,
                            uniqueCaptured = previous.uniqueCaptured + newEntries.size,
                            scanCycles = previous.scanCycles + 1,
                            lastSuccessAtMillis = System.currentTimeMillis(),
                            lastBatchCaptured = newEntries.size,
                        )
                    }
                    attempt = 0
                    delay(2500L)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    MonitorState.error("Logcat: " + (error.message ?: "capture error"))
                    resetGate.applyIfCurrent(resetGate.generation()) {
                        val previous = MonitorState.captureStats.value
                        MonitorState.captureStats.value = previous.copy(
                            readFailures = previous.readFailures + 1)
                    }
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
