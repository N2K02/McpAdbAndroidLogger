package dev.n2k02.mcplogger.adb

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Consumer-side transport observations until adb-core PR #9 is published.
 * Uses the same bounded nearest-rank model, without retaining shell commands,
 * pairing codes, outputs, network addresses or private identities.
 */
internal object AdbConnectionTelemetry {
    enum class Stage { CONNECT, SHELL }
    data class Sample(val stage: Stage, val durationMs: Long, val success: Boolean)
    private const val CAPACITY = 60
    private val history = ArrayDeque<Sample>()

    @Synchronized fun record(stage: Stage, elapsedNs: Long, success: Boolean) {
        if (history.size == CAPACITY) history.removeFirst()
        history.addLast(Sample(stage, (elapsedNs / 1_000_000L).coerceAtLeast(0), success))
    }

    @Synchronized fun clear() { history.clear() }

    @Synchronized fun snapshot(connected: Boolean): JSONObject {
        val stages = JSONArray()
        Stage.entries.forEach { stage ->
            val entries = history.filter { it.stage == stage }
            if (entries.isNotEmpty()) {
                val sorted = entries.map { it.durationMs }.sorted()
                fun p(percent: Int) = sorted[((sorted.size * percent + 99) / 100 - 1)
                    .coerceIn(0, sorted.lastIndex)]
                stages.put(JSONObject().put("stage", stage.name.lowercase())
                    .put("samples", entries.size)
                    .put("failures", entries.count { !it.success })
                    .put("p50_ms", p(50)).put("p95_ms", p(95)))
            }
        }
        return JSONObject().put("status", "ok")
            .put("connected", connected)
            .put("source", "mcp_app_observed")
            .put("scope", "MCP app connection calls and shell calls; not individual TLS handshakes")
            .put("window_max_samples", CAPACITY)
            .put("stages", stages)
            .put("note", "Includes lock contention and failures; does not measure device-wide ADB or video latency")
    }
}
