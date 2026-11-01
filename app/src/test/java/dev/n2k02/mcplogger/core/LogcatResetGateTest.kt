package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test

class LogcatResetGateTest {
    @Test fun stalePollNeverRestoresClearedLogs() {
        val gate = LogcatResetGate()
        val old = gate.generation()
        var restored = false
        gate.reset { }
        assertFalse(gate.applyIfCurrent(old) { restored = true })
        assertFalse(restored)
        assertTrue(gate.applyIfCurrent(gate.generation()) { restored = true })
        assertTrue(restored)
    }

    @Test fun systemClearBlocksReadsEvenWhileAdbCommandIsRunning() {
        val gate = LogcatResetGate()
        val previous = gate.generation()
        assertTrue(gate.beginDeviceClear { })
        assertFalse(gate.beginDeviceClear { })
        assertFalse(gate.applyIfCurrent(gate.generation()) { fail("Must not add stale events") })
        gate.finishDeviceClear { }
        assertFalse(gate.applyIfCurrent(previous) { fail("Old generation resumed") })
        assertTrue(gate.applyIfCurrent(gate.generation()) { })
    }

    @Test fun deviceClearCompletionReleasesGateEvenWhenCallbackFails() {
        val gate = LogcatResetGate()
        assertTrue(gate.beginDeviceClear { })
        assertThrows(IllegalStateException::class.java) {
            gate.finishDeviceClear { error("unexpected") }
        }
        assertTrue(gate.applyIfCurrent(gate.generation()) { })
    }

    @Test fun logcatClearIsAllBuffersAndRequiresSuccessMarker() {
        assertTrue(DeviceLogcatReset.COMMAND.contains("logcat -b all -c"))
        assertTrue(DeviceLogcatReset.succeeded("\\n__MCP_LOGCAT_CLEARED__\\n".replace("\\n", "\n")))
        assertFalse(DeviceLogcatReset.succeeded("__MCP_LOGCAT_FAILED__"))
        assertFalse(DeviceLogcatReset.succeeded(""))
    }
}
