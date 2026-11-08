package dev.n2k02.mcplogger.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        const val CLEAR_DEVICE_LOGCAT = "dev.n2k02.mcplogger.CLEAR_DEVICE_LOGCAT"
        const val LOCAL_STOP = "dev.n2k02.mcplogger.LOCAL_MCP_STOP"
        const val QUIT = "dev.n2k02.mcplogger.QUIT"
        private val buffer = LogBuffer()
        private val mcpBuffer = LogBuffer()
        private val resetGate = LogcatResetGate()
        private val readLock = Mutex()
        private const val IDLE_CACHE_MILLIS = 5 * 60_000L
        private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private val idleCleanup = Runnable {
            if (!MonitorState.active.value) {
                resetGate.reset {
                    buffer.clear(resetDedup = true)
                    mcpBuffer.clear(resetDedup = true)
                    MonitorState.logs.value = emptyList()
                    MonitorState.recentLogRequest.value = null
                }
            }
        }
        private fun cancelIdleLogCleanup() = idleHandler.removeCallbacks(idleCleanup)
        private fun scheduleIdleLogCleanup() {
            cancelIdleLogCleanup()
            idleHandler.postDelayed(idleCleanup, IDLE_CACHE_MILLIS)
        }
        private var logDayStart = -1L

        /** Rotate only the private in-memory capture and stats, never Android's logd. */
        private fun rotateDay(now: Long): Boolean {
            val today = LogDayPolicy.dayStart(now)
            if (logDayStart == today) return false
            if (logDayStart >= 0L) {
                resetGate.reset {
                    buffer.clear(resetDedup = true)
                    MonitorState.logs.value = emptyList()
                    MonitorState.recentLogRequest.value = null
                    MonitorState.captureStats.value = LogCaptureStats(dayStartMs = today)
                    MonitorState.logcatMaintenanceStatus.value = "Daily cache rotated; previous-day entries pruned"
                }
            } else {
                // App process may recreate its foreground service without restarting.
                buffer.clear(resetDedup = true)
                mcpBuffer.clear(resetDedup = true)
                MonitorState.logs.value = emptyList()
                MonitorState.recentLogRequest.value = null
                MonitorState.captureStats.value = LogCaptureStats(dayStartMs = today)
            }
            logDayStart = today
            return true
        }
        private val packageListLock = Mutex()
        private var lastPackagesRefreshedAtMs = -1L
        fun snapshot(after: Long = 0, limit: Int = 300) = buffer.snapshot(after, limit)

        /**
         * Android's logd already retains a circular buffer. A foreground MCP
         * connection must NOT poll or stream it: one explicit request runs one
         * bounded "logcat -d", then the shell command completes. The 2,000-entry
         * cache preserves existing MCP cursors and the local Logs viewer, but is
         * never replenished in the background.
         */
        internal suspend fun readOnDemand(origin: LogReadOrigin = LogReadOrigin.MCP,
            options: DiagnosticLogcat.Options? = null,
            commandTimeoutMs: Long = 12000): List<LogEvent> = readLock.withLock {
            if (origin == LogReadOrigin.MANUAL) cancelIdleLogCleanup()
            rotateDay(System.currentTimeMillis())
            val readGeneration = resetGate.generation()
            if (origin == LogReadOrigin.MANUAL) {
                MonitorState.logReadOrigin.value = origin
                MonitorState.active.value = true
            }
            try {
                check(AdbCoordinator.verified.value) { "ADB shell UID 2000 is not verified" }
                // Package identities rarely change; avoid invoking PackageManager for
                // every remote MCP log query while keeping an explicit refresh path.
                if (origin == LogReadOrigin.MANUAL ||
                    MonitorState.packageUids.value.isEmpty())
                    refreshInstalledApps(force = false)
                val output = AdbTransport.exec(
                    DiagnosticLogcat.captureCommand(options),
                    timeoutMs = commandTimeoutMs.coerceIn(1000, 12000)
                ).getOrThrow()
                val scannedAt = System.currentTimeMillis()
                val parsed = LogDayPolicy.recentToday(
                    output.lineSequence().mapNotNull(LogFoxParser::parse).toList(), scannedAt)
                // Serialize manual Clear with the midnight rollover: an in-flight
                // read must never repopulate entries the user explicitly cleared.
                if (origin == LogReadOrigin.MCP) {
                    // Keep every remote query outside the manual viewer, counters
                    // and cache. Preserve MCP cursor IDs in its own bounded buffer.
                    val accepted = resetGate.applyIfCurrent(readGeneration) {
                        mcpBuffer.add(parsed, null, null)
                        MonitorState.registerMcpRead()
                    }
                    if (accepted) mcpBuffer.snapshot(0, 2000) else emptyList()
                } else {
                    val accepted = resetGate.applyIfCurrent(readGeneration) {
                        rotateDay(scannedAt)
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
                    if (accepted) {
                        MonitorState.recentLogRequest.value = RecentLogRequest(
                            origin = LogReadOrigin.MANUAL,
                            tool = "Manual refresh",
                            atMillis = System.currentTimeMillis()
                        )
                        buffer.snapshot(0, 2000)
                    } else emptyList()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (origin == LogReadOrigin.MANUAL) resetGate.applyIfCurrent(readGeneration) {
                    val previous = MonitorState.captureStats.value
                    MonitorState.captureStats.value = previous.copy(
                        readFailures = previous.readFailures + 1
                    )
                }
                MonitorState.error("Logcat: " + (error.message ?: "read failed"))
                throw error
            } finally {
                if (origin == LogReadOrigin.MANUAL) {
                    MonitorState.active.value = false
                    MonitorState.logReadOrigin.value = null
                    scheduleIdleLogCleanup()
                }
            }
        }

        /** Explicit app-list requests force fresh data. Log snapshots reuse a
         *  recent verified UID list to avoid repeated PackageManager shell work. */
        suspend fun refreshInstalledApps(force: Boolean = true): List<String> =
            packageListLock.withLock {
                check(AdbCoordinator.verified.value) { "ADB shell UID 2000 is not verified" }
                val now = SystemClock.elapsedRealtime()
                if (!PackageRefreshPolicy.shouldRefresh(
                        now, lastPackagesRefreshedAtMs,
                        MonitorState.packageUids.value.isNotEmpty(), force)) {
                    return@withLock MonitorState.packages.value
                }
                val output = AdbTransport.exec("pm list packages -3 -U",
                    timeoutMs = 12000).getOrThrow()
                val known = PackageListParser.parse(output)
                MonitorState.packageUids.value = known
                MonitorState.packages.value = known.keys.sorted().take(500)
                lastPackagesRefreshedAtMs = SystemClock.elapsedRealtime()
                MonitorState.packages.value
            }
        /** Clear app-only cached Logcat records; retain Android system buffers. */
        fun clear() = resetGate.reset {
            buffer.clear()
            MonitorState.logs.value = emptyList()
            MonitorState.recentLogRequest.value = null
        }
        /** Home metrics only; ADB and tunnel status always remain truthful. */
        fun resetHomeStatistics() = resetGate.reset {
            MonitorState.captureStats.value = LogCaptureStats(
                dayStartMs = LogDayPolicy.dayStart(System.currentTimeMillis()))
            MonitorState.errors.value = emptyList()
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var tunnelJob: Job? = null
    private var relayHeartbeatJob: Job? = null
    private var dailyPruneJob: Job? = null
    private var quitJob: Job? = null
    private var foreground = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Never restart monitoring or the MCP listener while Quit is closing ADB.
        if (quitJob?.isActive == true) return START_NOT_STICKY
        DiagnosticProfileStore.load(applicationContext)
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
                                mcpBuffer.clear(resetDedup = true)
                                MonitorState.logs.value = emptyList()
                                MonitorState.captureStats.value = LogCaptureStats(
                                    dayStartMs = LogDayPolicy.dayStart(System.currentTimeMillis()))
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
                if (quitJob?.isActive == true) return START_NOT_STICKY
                dailyPruneJob?.cancel()
                dailyPruneJob = null
                stopCapture()
                mcpBuffer.clear(resetDedup = true)
                clear()
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
                MonitorState.quit()
                // Keep the Service alive until ADB socket teardown completes:
                // stopSelf triggers onDestroy, which cancels the Service scope.
                quitJob = scope.launch {
                    try {
                        withContext(NonCancellable) { AdbCoordinator.quit() }
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            foreground = false
                            MonitorState.foregroundServiceActive.value = false
                            stopSelf()
                        }
                    }
                }
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
                    MonitorState.foregroundServiceActive.value = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            TUNNEL_RESTART -> {
                getSharedPreferences("mcp_logger", MODE_PRIVATE).edit()
                    .putBoolean("mcp_server_enabled", true).apply()
                ensureForeground()
                LocalMcpServer.start(applicationContext)
                val previousTunnel = tunnelJob
                QuickMcpTunnel.stop()
                previousTunnel?.cancel()
                tunnelJob = scope.launch {
                    // The old supervisor must release its child process first.
                    previousTunnel?.join()
                    delay(1200)
                    QuickMcpTunnel.start(applicationContext)
                }
                ensureRelayHeartbeat()
            }
            LOCAL_START, START -> {
                ensureDailyPruning()
                // A boot-started foreground service must own ADB recovery even
                // when MainActivity never opens. discover() is idempotent.
                if (!AdbRecoverySettings.manuallyQuit(applicationContext))
                    AdbRecoveryLifecycle.start(applicationContext)
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

    /** One timed rollover per local day while the foreground service is alive.
     * This never runs a shell command or fetches new logs. */
    private fun ensureDailyPruning() {
        if (dailyPruneJob?.isActive == true) return
        dailyPruneJob = scope.launch {
            while (isActive) {
                readLock.withLock { rotateDay(System.currentTimeMillis()) }
                runCatching { McpAuditStore.prune(applicationContext) }
                val now = System.currentTimeMillis()
                delay((LogDayPolicy.nextMidnight(now) - now + 1000L).coerceAtLeast(1000L))
            }
        }
    }

    /**
     * Workers expire the phone route after four minutes for safety. Renew it
     * every minute only while cloudflared confirms a live edge connection.
     * If Android stops this foreground service, the route eventually expires.
     */
    private fun ensureRelayHeartbeat() {
        if (relayHeartbeatJob?.isActive == true) return
        relayHeartbeatJob = scope.launch {
            val recovery = QuickMcpTunnel.OfflineHeartbeatGate()
            while (isActive) {
                delay(60_000L)
                if (StableMcpRelay.publicMcpUrl(applicationContext) != null) {
                    val renewed = QuickMcpTunnel.refreshStableRelay(applicationContext)
                    if (!renewed && MonitorState.tunnel.value == "Public MCP connected")
                        MonitorState.tunnel.value = "Stable relay unavailable"
                    if (renewed && MonitorState.tunnel.value == "Stable relay unavailable")
                        MonitorState.tunnel.value = "Public MCP connected"
                    // A cloudflared process can survive while all its edge
                    // connections are lost. Reuse this existing heartbeat to
                    // perform one debounced restart with the SAME saved URL.
                    // Never restart if the edge is live but the Worker failed.
                    if (recovery.onHeartbeat(QuickMcpTunnel.isEdgeConnected()) &&
                        tunnelJob?.isActive == true) {
                        startService(Intent(this@LogMonitorService,
                            LogMonitorService::class.java).apply {
                            action = TUNNEL_RESTART
                        })
                    }
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
        MonitorState.foregroundServiceActive.value = true
    }

    /** One explicit Home/Logs refresh; never start a repeating polling loop. */
    private fun startCapture() {
        if (job?.isActive == true || MonitorState.active.value) return
        getSharedPreferences("mcp_logger", MODE_PRIVATE).edit()
            .putBoolean("manual_quit", false).apply()
        job = scope.launch {
            try {
                readOnDemand(LogReadOrigin.MANUAL)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // readOnDemand records the sanitized failure in MonitorState.
            }
        }
    }

    private fun stopCapture() {
        job?.cancel()
        job = null
    }
    override fun onDestroy() {
        dailyPruneJob?.cancel()
        stopCapture()
        QuickMcpTunnel.stop()
        tunnelJob?.cancel()
        relayHeartbeatJob?.cancel()
        LocalMcpServer.stop()
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        MonitorState.foregroundServiceActive.value = false
        scope.cancel()
        super.onDestroy()
    }
}
