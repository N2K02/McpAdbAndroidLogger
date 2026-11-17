package dev.n2k02.mcplogger.adb

import android.content.Context
import dev.n2k02.mcplogger.shizuku.ShizukuSession
import dev.n2k02.mcplogger.shizuku.ShizukuSessionRegistry
import dev.n2k02.mcplogger.shizuku.ShizukuPreferences

/** Owns the sole Shizuku session used by the app's shell operations. */
internal object ShizukuHostAdapter {
    @Volatile private var active: ShizukuSession? = null

    fun core(context: Context): ShizukuSession =
        active ?: synchronized(this) {
            active ?: ShizukuSession(context.applicationContext, Choices(context.applicationContext))
                .also { active = it; ShizukuSessionRegistry.install(it) }
        }

    fun current(): ShizukuSession? = active

    suspend fun shutdown() {
        val previous = synchronized(this) { active.also { active = null } }
        previous?.let {
            ShizukuSessionRegistry.uninstall(it)
            it.quit()
        }
    }

    private class Choices(private val app: Context) : ShizukuPreferences {
        override fun manuallyStopped() = AdbRecoverySettings.manuallyQuit(app)
        override fun setManuallyStopped(stopped: Boolean) =
            AdbRecoverySettings.setManuallyQuit(app, stopped)
        override fun lastVerifiedAt() = AdbRecoverySettings.lastVerifiedAt(app)
        override fun setLastVerifiedAt(timeMs: Long) =
            AdbRecoverySettings.setLastVerifiedAt(app, timeMs)
    }
}
