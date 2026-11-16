package dev.n2k02.mcplogger.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReadOnlyRetryPolicyTest {
    @Test fun allowsOnlyKnownReadOperations() {
        val accepted = listOf("dumpsys battery", "dumpsys jobscheduler | head -c 196608",
            "dumpsys SurfaceFlinger --list | head -c 100",
            "top -b -n 1 -m 200 -o PID,%CPU,RES,NAME -s 2",
            "cat /proc/meminfo; echo __MCP_PSI__; cat /proc/pressure/memory 2>&1",
            "pm list packages | head -n 2500")
        accepted.forEach { assertTrue(it, ReadOnlyRetryPolicy.canReplay(it)) }
    }

    @Test fun rejectsCommandsWithSideEffectsEvenUnderSafePrefixes() {
        val rejected = listOf("pm grant foo android.permission.DUMP",
            "dumpsys SurfaceFlinger --timestats -clear -enable",
            "dumpsys SurfaceFlinger --timestats -disable",
            "dumpsys battery --reset",
            "dumpsys power; settings put global adb_enabled 0",
            "logcat -c", "tcpip:5555",
            "id -u")
        rejected.forEach { assertFalse(it, ReadOnlyRetryPolicy.canReplay(it)) }
    }

    @Test fun retriesOnlyDisconnectedTransportNotPermissionsOrTimeouts() {
        assertTrue(ReadOnlyRetryPolicy.isRecoverable(IOException("socket closed")))
        assertTrue(ReadOnlyRetryPolicy.isRecoverable(IllegalStateException("ADB transport disconnected")))
        assertFalse(ReadOnlyRetryPolicy.isRecoverable(SecurityException("UID 2000 unavailable")))
        assertFalse(ReadOnlyRetryPolicy.isRecoverable(
            IllegalStateException("diagnostic_timeout")))
        assertFalse(ReadOnlyRetryPolicy.isRecoverable(
            IllegalArgumentException("Invalid command")))
    }
}
