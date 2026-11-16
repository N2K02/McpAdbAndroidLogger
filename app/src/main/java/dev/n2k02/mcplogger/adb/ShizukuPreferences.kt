package dev.n2k02.mcplogger.adb

import android.content.Context

/**
 * App-specific Shizuku preference, deliberately outside the shared ADB library.
 * Enabled by default for existing and new installs. This never grants permission:
 * Shizuku approval remains a separate explicit Android user action.
 */
internal object ShizukuPreferences {
    private const val FILE = "mcp_logger_shizuku"
    private const val KEY = "enabled"
    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
