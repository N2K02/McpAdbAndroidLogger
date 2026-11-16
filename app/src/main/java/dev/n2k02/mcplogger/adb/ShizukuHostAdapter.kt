package dev.n2k02.mcplogger.adb

import android.content.Context
import dev.n2k02.mcplogger.shizuku.ShizukuSession
import dev.n2k02.mcplogger.shizuku.ShizukuSessionRegistry
import dev.n2k02.mcplogger.shizuku.ShizukuHostHooks
import dev.n2k02.mcplogger.shizuku.ShizukuOption
import dev.n2k02.mcplogger.shizuku.ShizukuPreferences

/** Migration factory; preserve existing ADB authorization and user options.
 * Instantiate ONLY after shutting down the older direct ADB coordinator. */
internal object ShizukuHostAdapter {
    @Volatile private var active: ShizukuSession? = null

    /** Exactly one Shizuku user-service connection. Other app components share this core. */
    fun core(context: Context): ShizukuSession =
        active ?: synchronized(this) {
            active ?: create(context).also {
                active = it
                ShizukuSessionRegistry.install(it)
            }
        }

    fun current(): ShizukuSession? = active

    /** Quit must retire the sole transport; never allow legacy fallback. */
    suspend fun shutdown() {
        val previous = synchronized(this) { active.also { active = null } }
        previous?.let {
            ShizukuSessionRegistry.uninstall(it)
            it.quit()
        }
    }

    fun create(context: Context): ShizukuSession {
        val app = context.applicationContext
        lateinit var adb: ShizukuSession
        val hooks = object : ShizukuHostHooks {
            override suspend fun onRestoreAfterRestartChanged(enabled: Boolean) {
                // Opt-in grant and opt-out revoke must both preserve MCP's
                // permission lifecycle when the shared Settings page changes.
                if (AdbRecoverySettings.hasSecureSettings(app) == enabled) return
                val pkg = app.packageName.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) }
                    ?: return
                val action = if (enabled) "grant" else "revoke"
                // The core schedules verification callbacks outside the transport mutex.
                runCatching {
                    adb.execVerified("pm $action $pkg android.permission.WRITE_SECURE_SETTINGS", 10_000)
                }
            }
        }
        adb = ShizukuSession(app, Choices(app), hooks)
        return adb
    }

    private class Choices(private val app: Context) : ShizukuPreferences {
        private val legacy = app.getSharedPreferences("mcp_logger", Context.MODE_PRIVATE)
    override fun enabled(option: ShizukuOption) = when (option) {
        ShizukuOption.AUTO_PAIR -> AdbRecoverySettings.autoPair(app)
        ShizukuOption.WATCHDOG -> AdbRecoverySettings.watchdog(app)
        ShizukuOption.START_ON_BOOT -> AdbRecoverySettings.startOnBoot(app)
        ShizukuOption.RESTORE_AFTER_RESTART -> AdbRecoverySettings.restoreAfterRestart(app)
        ShizukuOption.TCP_5555 -> AdbRecoverySettings.tcpMode(app)
    }
    override fun setEnabled(option: ShizukuOption, enabled: Boolean) {
        when (option) {
            ShizukuOption.AUTO_PAIR -> AdbRecoverySettings.setAutoPair(app, enabled)
            ShizukuOption.WATCHDOG -> {
                AdbRecoverySettings.setWatchdog(app, enabled)
                // Shared settings must also update WorkManager's persistent
                // 15-minute backstop, not only the in-process core watchdog.
                if (enabled && !AdbRecoverySettings.manuallyQuit(app))
                    RecoveryWork.schedule(app)
                else RecoveryWork.cancel(app)
            }
            ShizukuOption.START_ON_BOOT -> AdbRecoverySettings.setStartOnBoot(app, enabled)
            ShizukuOption.RESTORE_AFTER_RESTART -> AdbRecoverySettings.setRestoreAfterRestart(app, enabled)
            ShizukuOption.TCP_5555 -> AdbRecoverySettings.setTcpMode(app, enabled)
        }
    }
    override fun manuallyStopped() = AdbRecoverySettings.manuallyQuit(app)
    override fun setManuallyStopped(stopped: Boolean) {
        AdbRecoverySettings.setManuallyQuit(app, stopped)
    }
    override fun lastTlsPort() = AdbRecoverySettings.lastVerifiedTlsPort(app)
    override fun setLastTlsPort(port: Int) { AdbRecoverySettings.rememberVerifiedTls(app, port) }
    override fun lastVerifiedAt() = legacy.getLong("core_verified_at", 0L)
    override fun setLastVerifiedAt(timeMs: Long) {
        legacy.edit().putLong("core_verified_at", timeMs).apply()
    }
    override fun wasAuthorized() = AdbRecoverySettings.paired(app)
    override fun setAuthorized() { AdbRecoverySettings.rememberPaired(app) }
    }
}
