package dev.n2k02.mcplogger.core

/**
 * Invalidates already-running Logcat polls when logs or statistics are reset.
 * Blocks newly completed polls while Android system buffers are being cleared.
 */
internal class LogcatResetGate {
    private var epoch = 0L
    private var clearing = false

    @Synchronized fun generation(): Long = epoch

    @Synchronized fun applyIfCurrent(expected: Long, block: () -> Unit): Boolean {
        if (clearing || epoch != expected) return false
        block()
        return true
    }

    @Synchronized fun reset(block: () -> Unit) {
        epoch++
        block()
    }

    @Synchronized fun beginDeviceClear(block: () -> Unit): Boolean {
        if (clearing) return false
        clearing = true
        epoch++
        block()
        return true
    }

    @Synchronized fun finishDeviceClear(block: () -> Unit) {
        try {
            epoch++
            block()
        } finally {
            clearing = false
        }
    }
}

internal object DeviceLogcatReset {
    // Only the locally verified UID 2000 ADB shell can execute this command.
    const val COMMAND =
        "logcat -b all -c >/dev/null 2>&1 && echo __MCP_LOGCAT_CLEARED__ || echo __MCP_LOGCAT_FAILED__"

    fun succeeded(output: String): Boolean =
        output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ==
            "__MCP_LOGCAT_CLEARED__"
}
