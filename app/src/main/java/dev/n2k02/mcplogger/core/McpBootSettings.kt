package dev.n2k02.mcplogger.core

import android.content.Context

/** User preference for starting the local MCP server and tunnel after boot. */
object McpBootSettings {
    const val DEFAULT_START_ON_BOOT = true
    private const val PREFS = "mcp_logger"
    private const val KEY_START_ON_BOOT = "mcp_start_on_boot"

    fun startOnBoot(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_START_ON_BOOT, DEFAULT_START_ON_BOOT)

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_START_ON_BOOT, enabled)
            .apply()
    }

    fun shouldStartOnBoot(enabled: Boolean, serverEnabled: Boolean, manuallyQuit: Boolean): Boolean =
        enabled && serverEnabled && !manuallyQuit
}
