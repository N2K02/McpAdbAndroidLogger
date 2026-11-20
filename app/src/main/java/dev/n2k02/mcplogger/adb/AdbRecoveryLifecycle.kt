package dev.n2k02.mcplogger.adb

import android.content.Context
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

/** Health checks only: never enable wireless debugging, pair or open ADB TCP. */
object AdbRecoveryLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveryMutex = Mutex()
    private var healthJob: Job? = null
    @Volatile private var quitting = false
    private var failedAttempts = 0
    private var nextAllowedTime = 0L

    @Synchronized fun start(context: Context) {
        val app = context.applicationContext
        if (AdbRecoverySettings.manuallyQuit(app)) return
        quitting = false
        AdbCoordinator.discover(app)
        configureWatchdog(app, AdbRecoverySettings.watchdog(app))
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
                recoverOnce(app, "Shizuku watchdog")
                delay(120_000)
            }
        }
    }

    suspend fun recoverOnce(context: Context, reason: String): Boolean =
        recoveryMutex.withLock {
            val app = context.applicationContext
            if (quitting || !AdbRecoverySettings.backgroundRecoveryAllowed(app))
                return@withLock false
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < nextAllowedTime) return@withLock false
            try {
                if (AdbCoordinator.verified.value && AdbTransport.isConnected) {
                    val uid = AdbTransport.exec("id -u", timeoutMs = 6_000).getOrNull()?.trim()
                    if (uid == "2000") {
                        failedAttempts = 0
                        nextAllowedTime = 0L
                        return@withLock true
                    }
                    AdbCoordinator.verified.value = false
                }
                val success = AdbCoordinator.connect(app).isSuccess
                if (success) {
                    failedAttempts = 0
                    nextAllowedTime = 0L
                } else {
                    failedAttempts = (failedAttempts + 1).coerceAtMost(6)
                    nextAllowedTime = now + (60_000L shl (failedAttempts - 1)).coerceAtMost(30 * 60_000L)
                }
                success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MonitorState.error("Shizuku recovery ($reason): " + error.javaClass.simpleName)
                false
            }
        }

    @Synchronized fun shutdown(context: Context) {
        quitting = true
        healthJob?.cancel()
        healthJob = null
        scope.coroutineContext.cancelChildren()
        RecoveryWork.cancel(context.applicationContext)
    }
}

/** Periodic verification only; LocalADB owns Shizuku server startup. */
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
        } catch (_: Exception) { }
        return Result.success()
    }
}

internal object RecoveryWork {
    private const val NAME = "mcp-shizuku-watchdog"
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
