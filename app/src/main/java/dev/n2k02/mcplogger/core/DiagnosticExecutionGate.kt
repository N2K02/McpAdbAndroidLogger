package dev.n2k02.mcplogger.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Fair, bounded admission for on-demand diagnostics. Keeps OAuth/HTTP workers
 * responsive; calls serialize at the shared ADB core only when actually needed.
 * The wait is measured separately so it is never reported as device latency.
 */
internal object DiagnosticExecutionGate {
    private const val MAX_WAIT_MS = 8_000L
    private const val MAX_HISTORY = 60
    private val slots = Semaphore(2, true)
    private data class Sample(val waitMs: Long, val executionMs: Long, val success: Boolean)
    private val history = ArrayDeque<Sample>()

    fun <T> execute(block: () -> T): T {
        val queuedAt = System.nanoTime()
        val acquired = try {
            slots.tryAcquire(MAX_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("diagnostic_busy")
        }
        val waitMs = ((System.nanoTime() - queuedAt) / 1_000_000L).coerceAtLeast(0L)
        if (!acquired) {
            record(Sample(waitMs, 0, false))
            throw IllegalStateException("diagnostic_busy")
        }
        val started = System.nanoTime()
        var successful = false
        try {
            return block().also { successful = true }
        } finally {
            record(Sample(waitMs,
                ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L),
                successful))
            slots.release()
        }
    }

    @Synchronized private fun record(sample: Sample) {
        if (history.size == MAX_HISTORY) history.removeFirst()
        history.addLast(sample)
    }

    @Synchronized fun snapshot(): JSONObject {
        val sortedWait = history.map { it.waitMs }.sorted()
        val sortedExecution = history.map { it.executionMs }.sorted()
        fun percentile(values: List<Long>, percentile: Int): Any =
            if (values.isEmpty()) JSONObject.NULL
            else values[((values.size * percentile + 99) / 100 - 1).coerceIn(0, values.lastIndex)]
        return JSONObject()
            .put("recent_requests", history.size)
            .put("failed_requests", history.count { !it.success })
            .put("queue_wait_p50_ms", percentile(sortedWait, 50))
            .put("queue_wait_p95_ms", percentile(sortedWait, 95))
            .put("execution_p50_ms", percentile(sortedExecution, 50))
            .put("execution_p95_ms", percentile(sortedExecution, 95))
            .put("active_request_slots", 2 - slots.availablePermits())
            .put("maximum_wait_ms", MAX_WAIT_MS)
            .put("window_max_samples", MAX_HISTORY)
    }

    @Synchronized fun reset() { history.clear() }
}
