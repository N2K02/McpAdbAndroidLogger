package dev.n2k02.mcplogger.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.n2k02.mcplogger.adb.AdbCoordinator
import dev.n2k02.mcplogger.adb.AdbRecoverySettings
import dev.n2k02.mcplogger.adb.AdbRecoveryLifecycle
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AdbCoordinator.autoStart(context)) return
        if (context.getSharedPreferences("mcp_logger", Context.MODE_PRIVATE)
                .getBoolean("manual_quit", false)) return
        // connectedDevice FGS may be launched on BOOT_COMPLETED for the
        // user-enabled ADB/tunnel connection; dataSync is not permitted on Android 15+.
        if (context.getSharedPreferences("mcp_logger", Context.MODE_PRIVATE)
                .getBoolean("mcp_server_enabled", true)) {
            runCatching {
                ContextCompat.startForegroundService(context,
                    Intent(context, LogMonitorService::class.java)
                        .setAction(LogMonitorService.LOCAL_START))
            }.onFailure { MonitorState.error("MCP boot restart blocked by Android") }
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            "adb-boot-recovery", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AdbRecoveryWorker>().build())
    }
}
class AdbRecoveryWorker(context: Context, args: WorkerParameters) : CoroutineWorker(context, args) {
    override suspend fun doWork(): Result {
        if (!AdbCoordinator.autoStart(applicationContext) ||
            applicationContext.getSharedPreferences("mcp_logger", Context.MODE_PRIVATE)
                .getBoolean("manual_quit", false)) return Result.success()
        AdbRecoveryLifecycle.recoverOnce(applicationContext, "boot")
        return Result.success()
    }
}
