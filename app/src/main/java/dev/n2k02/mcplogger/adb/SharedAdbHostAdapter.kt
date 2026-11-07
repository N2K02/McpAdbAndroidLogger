package dev.n2k02.mcplogger.adb

import android.content.Context
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.AdbIdentity as CoreIdentity
import dev.n2k02.adb.core.AdbIdentitySource
import dev.n2k02.adb.core.AdbOption
import dev.n2k02.adb.core.AdbPreferenceStore
import dev.n2k02.adb.core.LibAdbWire
import dev.n2k02.adb.core.LocalAdbDiscovery

/** Migration factory; preserve existing ADB authorization and user options.
 * Instantiate ONLY after shutting down the legacy ADB coordinator. */
internal object SharedAdbHostAdapter {
    fun create(context: Context): AdbCore {
        val app = context.applicationContext
        val identity = AdbIdentitySource { ctx ->
            val existing = AdbIdentity.getOrCreate(ctx)
            CoreIdentity(existing.privateKey, existing.certificate)
        }
        return AdbCore(LibAdbWire(app, identity, "McpAdbAndroidLogger"), Choices(app), LocalAdbDiscovery(app))
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
