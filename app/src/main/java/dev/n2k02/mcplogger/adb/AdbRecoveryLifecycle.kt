package dev.n2k02.mcplogger.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.n2k02.mcplogger.core.MonitorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** VRec-style lifecycle: one recovery job, 2-minute checks, 15-minute OS backstop. */
object AdbRecoveryLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryMutex = Mutex()
    private var healthJob: Job? = null
    private var wifi: ConnectivityManager.NetworkCallback? = null
    @Volatile private var quitting = false
    private var failedAttempts = 0
    private var nextAllowedTime = 0L

    @Synchronized fun start(context: Context) {
        val app = context.applicationContext
        if (AdbRecoverySettings.manuallyQuit(app)) return
        quitting = false
        AdbCoordinator.discover(app)
        configureWatchdog(app, AdbRecoverySettings.watchdog(app))
        if (wifi == null) {
            val manager = app.getSystemService(ConnectivityManager::class.java)
            if (manager != null) {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (!quitting && !AdbRecoverySettings.manuallyQuit(app))
                            scope.launch { recoverOnce(app, "Wi-Fi available") }
                    }
                }
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
                if (runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess)
                    wifi = callback
            }
        }
    }

    @Synchronized fun configureWatchdog(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        AdbRecoverySettings.setWatchdog(app, enabled)
        healthJob?.cancel()
        healthJob = null
        if (!enabled || quitting || AdbRecoverySettings.manuallyQuit(app)) {
            RecoveryWork.cancel(app)
            return
        }
        RecoveryWork.schedule(app)
        healthJob = scope.launch {
            recoverOnce(app, "app open")
            delay(45_000)
            while (isActive && AdbRecoverySettings.watchdog(app)) {
                recoverOnce(app, "connection watchdog")
                delay(120_000)
            }
        }
    }

    /** Called after boot, reconnection or by Android's persistent safety net. */
    suspend fun recoverOnce(context: Context, reason: String): Boolean =
        recoveryMutex.withLock {
            val app = context.applicationContext
            if (quitting || !AdbRecoverySettings.backgroundRecoveryAllowed(app))
                return@withLock false
            if (AdbCoordinator.manualRetryRequired.value && !AdbCoordinator.isTcpListening())
                return@withLock false
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < nextAllowedTime) return@withLock false
            try {
                if (AdbCoordinator.verified.value && AdbTransport.lastKnownConnected) {
                    val uid = AdbTransport.exec("id -u", timeoutMs = 6_000).getOrNull()?.trim()
                    if (uid == "2000") {
                        failedAttempts = 0
                        nextAllowedTime = 0L
                        return@withLock true
                    }
                    AdbCoordinator.verified.value = false
                }
                if (AdbRecoverySettings.restoreWirelessAfterReboot(app))
                    AdbCoordinator.discover(app)
                val result = AdbCoordinator.connect(app)
                if (result.isSuccess) {
                    failedAttempts = 0
                    nextAllowedTime = 0L
                    return@withLock true
                }
                if (!AdbCoordinator.manualRetryRequired.value) {
                    AdbCoordinator.discover(app)
                    failedAttempts = (failedAttempts + 1).coerceAtMost(6)
                    val interval = (60_000L shl (failedAttempts - 1)).coerceAtMost(30 * 60_000L)
                    nextAllowedTime = now + interval
                }
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MonitorState.error("ADB recovery (" + reason + "): " + error.javaClass.simpleName)
                false
            }
        }

    @Synchronized fun shutdown(context: Context) {
        val app = context.applicationContext
        quitting = true
        healthJob?.cancel()
        healthJob = null
        scope.coroutineContext.cancelChildren()
        wifi?.let {
            runCatching { app.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(it) }
        }
        wifi = null
        RecoveryWork.cancel(app)
    }
}

class AdbWatchdogWorker(context: Context, args: WorkerParameters) :
    CoroutineWorker(context, args) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!AdbRecoverySettings.watchdog(app) ||
            !AdbRecoverySettings.backgroundRecoveryAllowed(app)) return Result.success()
        try {
            AdbRecoveryLifecycle.recoverOnce(app, "OS watchdog")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // WorkManager will retry on its next periodic invocation.
        }
        return Result.success()
    }
}

/** WorkManager's earliest periodic interval is 15 minutes; Android may defer it. */
internal object RecoveryWork {
    private const val NAME = "adb-mcp-watchdog"
    fun schedule(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AdbWatchdogWorker>(15, TimeUnit.MINUTES).build())
        }
    }
    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME) }
    }
}
