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

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!AdbCoordinator.autoStart(context)) return
        if (context.getSharedPreferences("mcp_logger", Context.MODE_PRIVATE)
                .getBoolean("manual_quit", false)) return
        // Boot may not legally start a dataSync foreground service; perform a best-effort
        // connection probe and let the user explicitly resume monitoring.
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
        return if (AdbCoordinator.connect(applicationContext).isSuccess) Result.success() else Result.retry()
    }
}
