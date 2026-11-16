package dev.n2k02.mcplogger.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException

class AdbConnectionTelemetryClassificationTest {
    @Test fun classifiesErrorsWithoutExposingPrivateMessages() {
        assertEquals("authorization",
            AdbConnectionTelemetry.classify(SecurityException("secret uid token")))
        assertEquals("disconnected",
            AdbConnectionTelemetry.classify(IOException("private socket address")))
        assertEquals("timeout",
            AdbConnectionTelemetry.classify(TimeoutException("secret timeout")))
        assertEquals("cancelled",
            AdbConnectionTelemetry.classify(CancellationException("quit")))
        assertEquals("other",
            AdbConnectionTelemetry.classify(IllegalArgumentException("sensitive")))
    }

    @Test fun reportsOnlyFixedFailureCategoriesAndRecoveryCounts() {
        AdbConnectionTelemetry.clear()
        AdbConnectionTelemetry.record(AdbConnectionTelemetry.Stage.SHELL, 4_000_000, false,
            "disconnected", operationFamily = "surfaceflinger")
        AdbConnectionTelemetry.record(AdbConnectionTelemetry.Stage.SHELL, 5_000_000, true,
            recovered = true)
        val report = AdbConnectionTelemetry.snapshot(true)
        val shell = report.getJSONArray("stages").getJSONObject(0)
        assertEquals(1, shell.getInt("failures"))
        assertEquals(1, shell.getInt("recovered_calls"))
        assertEquals(1, shell.getJSONObject("failure_categories").getInt("disconnected"))
        assertEquals(1, shell.getJSONObject("failure_families").getInt("surfaceflinger"))
        assertEquals("memory", AdbConnectionTelemetry.family("cat /proc/meminfo"))
        assertEquals("perfetto", AdbConnectionTelemetry.family("printf 'cfg' | perfetto -c -"))
        assertEquals("other", AdbConnectionTelemetry.family("secret token from another app"))
        assertFalse(report.toString().contains("secret token"))
        assertFalse(report.toString().contains("private socket address"))
        AdbConnectionTelemetry.clear()
    }
}
