package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticExecutionGateTest {
    @Test fun countsExecutionAndFailuresWithoutLeakingInputs() {
        DiagnosticExecutionGate.reset()
        assertEquals(1, DiagnosticExecutionGate.execute { 1 })
        try {
            DiagnosticExecutionGate.execute<Int> { throw IllegalStateException("secret diagnostic stderr") }
            fail("Exception was swallowed")
        } catch (_: IllegalStateException) { }
        val snapshot = DiagnosticExecutionGate.snapshot()
        assertEquals(2, snapshot.getInt("recent_requests"))
        assertEquals(1, snapshot.getInt("failed_requests"))
        assertEquals(60, snapshot.getInt("window_max_samples"))
        assertFalse(snapshot.toString().contains("secret diagnostic stderr"))
        DiagnosticExecutionGate.reset()
    }

    @Test fun limitsConcurrencyToTwoAndDoesNotLeakPermitsOnFailure() {
        DiagnosticExecutionGate.reset()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val workers = (0 until 4).map {
            Thread {
                DiagnosticExecutionGate.execute {
                    val active = running.incrementAndGet()
                    maxRunning.updateAndGet { current -> maxOf(current, active) }
                    started.countDown()
                    try { release.await() } finally { running.decrementAndGet() }
                }
            }.apply { start() }
        }
        try {
            assertTrue("Did not start first two workers", started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(2, maxRunning.get())
        } finally {
            release.countDown()
            workers.forEach { it.join(4000) }
        }
        assertEquals(0, DiagnosticExecutionGate.snapshot().getInt("active_request_slots"))
        assertEquals(4, DiagnosticExecutionGate.snapshot().getInt("recent_requests"))
    }
}
