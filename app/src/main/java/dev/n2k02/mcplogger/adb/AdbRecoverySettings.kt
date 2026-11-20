package dev.n2k02.mcplogger.adb

import android.content.Context
import android.provider.Settings

/** Persistent Shizuku session preferences. No ADB pairing or TCP configuration. */
object AdbRecoverySettings {
    private const val PREF = "mcp_logger"
    private fun p(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun watchdog(context: Context) = p(context).getBoolean("connection_watchdog", true)
    fun setWatchdog(context: Context, enabled: Boolean) =
        p(context).edit().putBoolean("connection_watchdog", enabled).apply()

    fun startOnBoot(context: Context) = p(context).getBoolean("start_on_boot", true)
    fun setStartOnBoot(context: Context, enabled: Boolean) =
        p(context).edit().putBoolean("start_on_boot", enabled).apply()

    fun manuallyQuit(context: Context) = p(context).getBoolean("manual_quit", false)
    fun setManuallyQuit(context: Context, stopped: Boolean) =
        p(context).edit().putBoolean("manual_quit", stopped).commit()

    private fun bootCount(context: Context) = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    private fun lastForegroundBoot(context: Context) = p(context).getInt("adb_last_opened_boot", -1)

    fun markForegroundOpened(context: Context) {
        val boot = bootCount(context)
        if (boot >= 0) p(context).edit().putInt("adb_last_opened_boot", boot).apply()
        setManuallyQuit(context, false)
    }

    fun backgroundRecoveryAllowed(context: Context): Boolean {
        val boot = bootCount(context)
        return !manuallyQuit(context) &&
            (startOnBoot(context) || (boot >= 0 && lastForegroundBoot(context) == boot))
    }

    /** Diagnostic history only; never treated as a live permission grant. */
    fun lastVerifiedAt(context: Context): Long = p(context).getLong("core_verified_at", 0L)
    fun setLastVerifiedAt(context: Context, timeMs: Long) =
        p(context).edit().putLong("core_verified_at", timeMs).apply()
}
