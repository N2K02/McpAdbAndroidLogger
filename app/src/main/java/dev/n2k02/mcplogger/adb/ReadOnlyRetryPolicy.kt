package dev.n2k02.mcplogger.adb

import java.io.IOException

/** Only immutable, pre-defined, side-effect-free diagnostic reads may replay. */
internal object ReadOnlyRetryPolicy {
    private val commands = listOf(
        "dumpsys battery", "dumpsys batterystats", "dumpsys power",
        "dumpsys thermalservice", "dumpsys jobscheduler", "dumpsys audio",
        "dumpsys activity", "dumpsys SurfaceFlinger --list",
        "dumpsys SurfaceFlinger --timestats -dump", "dumpsys gfxinfo",
        "dumpsys meminfo", "dumpsys -l", "top ", "cat /proc/",
        "logcat -d", "pm list packages", "pidof ", "head -c "
    )
    private val disallowed = listOf("-clear", "-enable", "-disable",
        "--reset", "settings put", "pm grant", "pm revoke", "tcpip:", "usb:")
    // Compound shell commands are excluded even if prefixed by a safe read.
    // Only simple formatting pipes to approved read-only tools may replay.
    private fun simpleRead(command: String): Boolean {
        if (command.any { it == ';' || it == '\n' || it == '\r' ||
                    it == '`' || it == '>' || it == '<' }) return false
        if (listOf("$(", "&&", "||").any(command::contains)) return false
        return command.split("|").drop(1).all { pipe ->
            listOf("head ", "grep ", "tail ", "cut ").any {
                pipe.trimStart().startsWith(it)
            }
        }
    }

    fun canReplay(command: String): Boolean =
        commands.any { command.startsWith(it) } &&
            disallowed.none { command.contains(it, ignoreCase = true) } &&
            simpleRead(command)

    /** Do not replay application errors, authorization failures or timeouts. */
    fun isRecoverable(failure: Exception): Boolean =
        (failure is IOException && failure !is java.net.SocketTimeoutException) || (
            failure is IllegalStateException &&
                (failure.message?.contains("ADB is not connected", true) == true ||
                 failure.message?.contains("ADB transport disconnected", true) == true ||
                 failure.message?.contains("stream closed", true) == true)
        )
}
