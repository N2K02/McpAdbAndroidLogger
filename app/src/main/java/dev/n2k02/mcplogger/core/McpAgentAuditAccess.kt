package dev.n2k02.mcplogger.core

import android.content.Context

/**
 * Explicit on-device consent for exposing the private MCP audit journal.
 * OFF by default for new and upgraded installs; never writable through MCP.
 */
internal object McpAgentAuditAccess {
    private const val PREFS = "mcp_agent_audit_access"
    private const val READ_HISTORY = "allow_agent_audit_history"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(READ_HISTORY, false)

    /** Commit before reporting success to the user. */
    fun setEnabled(context: Context, allowed: Boolean): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(READ_HISTORY, allowed).commit()
}
