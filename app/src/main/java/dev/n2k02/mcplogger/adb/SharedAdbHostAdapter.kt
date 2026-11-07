package dev.n2k02.mcplogger.adb

import android.content.Context
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.AdbCoreRegistry
import dev.n2k02.adb.core.AdbHostHooks
import dev.n2k02.adb.core.AdbIdentity as CoreIdentity
import dev.n2k02.adb.core.AdbIdentitySource
import dev.n2k02.adb.core.AdbOption
import dev.n2k02.adb.core.AdbPreferenceStore
import dev.n2k02.adb.core.LibAdbWire
import dev.n2k02.adb.core.LocalAdbDiscovery

/** Migration factory; preserve existing ADB authorization and user options.
 * Instantiate ONLY after shutting down the legacy ADB coordinator. */
internal object SharedAdbHostAdapter {
    @Volatile private var active: AdbCore? = null

    /** Exactly one live libadb transport. Other app components share this core. */
    fun core(context: Context): AdbCore =
        active ?: synchronized(this) {
            active ?: create(context).also {
                active = it
                AdbCoreRegistry.install(it)
            }
        }

    fun current(): AdbCore? = active

    /** Quit must retire the sole transport; never allow legacy fallback. */
    suspend fun shutdown() {
        val previous = synchronized(this) { active.also { active = null } }
        previous?.let {
            AdbCoreRegistry.uninstall(it)
            it.quit()
        }
    }

    fun create(context: Context): AdbCore {
        val app = context.applicationContext
        val identity = AdbIdentitySource { ctx ->
            val existing = AdbIdentity.getOrCreate(ctx)
            CoreIdentity(existing.privateKey, existing.certificate)
        }
        lateinit var adb: AdbCore
        val hooks = object : AdbHostHooks {
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
        adb = AdbCore(LibAdbWire(app, identity, "McpAdbAndroidLogger"),
            Choices(app), LocalAdbDiscovery(app), hooks)
        return adb
    }

    private class Choices(private val app: Context) : AdbPreferenceStore {
        private val legacy = app.getSharedPreferences("mcp_logger", Context.MODE_PRIVATE)
    override fun enabled(option: AdbOption) = when (option) {
        AdbOption.AUTO_PAIR -> AdbRecoverySettings.autoPair(app)
        AdbOption.WATCHDOG -> AdbRecoverySettings.watchdog(app)
        AdbOption.START_ON_BOOT -> AdbRecoverySettings.startOnBoot(app)
        AdbOption.RESTORE_AFTER_RESTART -> AdbRecoverySettings.restoreAfterRestart(app)
        AdbOption.TCP_5555 -> AdbRecoverySettings.tcpMode(app)
    }
    override fun setEnabled(option: AdbOption, enabled: Boolean) {
        when (option) {
            AdbOption.AUTO_PAIR -> AdbRecoverySettings.setAutoPair(app, enabled)
            AdbOption.WATCHDOG -> AdbRecoverySettings.setWatchdog(app, enabled)
            AdbOption.START_ON_BOOT -> AdbRecoverySettings.setStartOnBoot(app, enabled)
            AdbOption.RESTORE_AFTER_RESTART -> AdbRecoverySettings.setRestoreAfterRestart(app, enabled)
            AdbOption.TCP_5555 -> AdbRecoverySettings.setTcpMode(app, enabled)
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
