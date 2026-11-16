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
    fun canReplay(command: String): Boolean =
        commands.any { command.startsWith(it) } &&
            disallowed.none { command.contains(it, ignoreCase = true) }

    /** Do not replay application errors, authorization failures or timeouts. */
    fun isRecoverable(failure: Exception): Boolean =
        failure is IOException || (
            failure is IllegalStateException &&
                (failure.message?.contains("ADB is not connected", true) == true ||
                 failure.message?.contains("ADB transport disconnected", true) == true ||
                 failure.message?.contains("stream closed", true) == true)
        )
}
