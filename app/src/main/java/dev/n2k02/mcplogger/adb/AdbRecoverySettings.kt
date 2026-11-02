package dev.n2k02.mcplogger.adb

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.io.IOException

/** VRec-compatible persistent user choices, recovery guards and boot-scoped endpoints. */
object AdbRecoverySettings {
    private const val PREF = "mcp_logger"
    private fun p(context: Context) = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun autoPair(context: Context) = p(context).getBoolean("automatic_pairing_code", true)
    fun setAutoPair(context: Context, enabled: Boolean) =
        p(context).edit().putBoolean("automatic_pairing_code", enabled).apply()

    fun watchdog(context: Context) = p(context).getBoolean("connection_watchdog", true)
    fun setWatchdog(context: Context, enabled: Boolean) =
        p(context).edit().putBoolean("connection_watchdog", enabled).apply()

    fun startOnBoot(context: Context) = p(context).getBoolean("start_on_boot", true)
    fun setStartOnBoot(context: Context, enabled: Boolean) =
        p(context).edit().putBoolean("start_on_boot", enabled).apply()

    fun restoreAfterRestart(context: Context) = p(context).getBoolean("restore_after_restart", false)
    fun setRestoreAfterRestart(context: Context, enabled: Boolean) =
        p(context).edit().putBoolean("restore_after_restart", enabled).apply()

    fun tcpMode(context: Context) = p(context).getBoolean("tcp_mode", true)
    fun setTcpMode(context: Context, enabled: Boolean) =
        p(context).edit().putBoolean("tcp_mode", enabled).apply()

    fun manuallyQuit(context: Context) = p(context).getBoolean("manual_quit", false)
    fun setManuallyQuit(context: Context, stopped: Boolean) =
        p(context).edit().putBoolean("manual_quit", stopped).commit()

    fun bootCount(context: Context): Int = runCatching {
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

    fun rememberPaired(context: Context) {
        p(context).edit().putBoolean("adb_pair_verified", true).apply()
    }
    fun paired(context: Context) = p(context).getBoolean("adb_pair_verified", false)

    fun rememberVerifiedTls(context: Context, port: Int) {
        if (port !in 1..65535) return
        val boot = bootCount(context)
        if (boot < 0) return
        p(context).edit().putInt("adb_last_tls_port", port)
            .putInt("adb_tls_port_boot", boot)
            .putInt("adb_last_verified_wireless_boot", boot)
            .putBoolean("adb_pair_verified", true).apply()
    }
    fun reusablePort(port: Int, savedBoot: Int, boot: Int): Int? =
        port.takeIf { it in 1..65535 && boot >= 0 && boot == savedBoot }
    fun lastVerifiedTlsPort(context: Context): Int? = reusablePort(
        p(context).getInt("adb_last_tls_port", -1),
        p(context).getInt("adb_tls_port_boot", -1), bootCount(context))
    fun clearVerifiedPort(context: Context, port: Int) {
        if (lastVerifiedTlsPort(context) == port) p(context).edit()
            .remove("adb_last_tls_port").remove("adb_tls_port_boot").apply()
    }
    fun lastVerifiedBoot(context: Context) =
        p(context).getInt("adb_last_verified_wireless_boot", -1)

    fun hasSecureSettings(context: Context) =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED

    fun onWifi(context: Context) = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    fun wirelessEnabled(context: Context) = runCatching {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
    }.getOrDefault(false)

    /** Only an already-authorized shell can grant this opt-in extra permission. */
    suspend fun grantSecureSettings(context: Context): Boolean {
        if (!restoreAfterRestart(context)) return false
        if (hasSecureSettings(context)) return true
        if (!AdbCoordinator.verified.value) return false
        AdbTransport.exec("pm grant " + context.packageName +
            " android.permission.WRITE_SECURE_SETTINGS", timeoutMs = 10_000)
        return hasSecureSettings(context)
    }
    suspend fun revokeSecureSettings(context: Context): Boolean {
        if (!hasSecureSettings(context)) return true
        if (!AdbCoordinator.verified.value) return false
        AdbTransport.exec("pm revoke " + context.packageName +
            " android.permission.WRITE_SECURE_SETTINGS", timeoutMs = 10_000)
        return !hasSecureSettings(context)
    }

    enum class RestoreDecision {
        NOT_OPTED_IN, NO_PERMISSION, NOT_PAIRED, NO_WIFI, MANUAL_QUIT,
        UNSUPPORTED, ALREADY_ON, USER_TURNED_OFF, RESTORE
    }

    /** Matches VRec: never override Wireless debugging disabled in this boot. */
    fun decideRestore(optedIn: Boolean, hasPermission: Boolean, paired: Boolean,
                      wifi: Boolean, quit: Boolean, sdk: Int,
                      wirelessOn: Boolean, boot: Int, lastBoot: Int): RestoreDecision = when {
        !optedIn -> RestoreDecision.NOT_OPTED_IN
        !hasPermission -> RestoreDecision.NO_PERMISSION
        !paired -> RestoreDecision.NOT_PAIRED
        quit -> RestoreDecision.MANUAL_QUIT
        sdk < 30 -> RestoreDecision.UNSUPPORTED
        !wifi -> RestoreDecision.NO_WIFI
        wirelessOn -> RestoreDecision.ALREADY_ON
        boot < 0 || lastBoot < 0 || boot == lastBoot -> RestoreDecision.USER_TURNED_OFF
        else -> RestoreDecision.RESTORE
    }

    fun restoreDecision(context: Context): RestoreDecision = decideRestore(
        restoreAfterRestart(context), hasSecureSettings(context), paired(context),
        onWifi(context), manuallyQuit(context), Build.VERSION.SDK_INT,
        wirelessEnabled(context), bootCount(context), lastVerifiedBoot(context))

    /** Returns only after Android accepts the setting; never bypasses system pairing. */
    fun restoreWirelessAfterReboot(context: Context): Boolean {
        val verdict = restoreDecision(context)
        if (verdict == RestoreDecision.ALREADY_ON) return true
        if (verdict != RestoreDecision.RESTORE) return false
        return runCatching {
            Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1) &&
                wirelessEnabled(context)
        }.getOrDefault(false)
    }

    /**
     * A stale TLS port or a temporary rejection during daemon restart is not
     * proof that pairing was revoked. Keep retrying automatically; reserve
     * manual intervention for explicit security/authorization exceptions.
     */
    fun requiresManualRetry(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is SecurityException }
}
