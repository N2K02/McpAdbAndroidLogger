package dev.n2k02.mcplogger.core

import android.os.SystemClock
import dev.n2k02.mcplogger.adb.AdbCoordinator
import dev.n2k02.mcplogger.adb.AdbTransport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.round

/**
 * Explicit short SurfaceFlinger TimeStats / CPU correlation and optional Perfetto
 * FrameTimeline + scheduler trace analysis. No background polling or raw trace
 * transfer. All shell commands/configs/SQL are internal fixed templates.
 *
 * TimeStats collection briefly enables device-wide instrumentation, clearing
 * its previous counters, and is always disabled after the sampling window.
 * This temporarily impacts *other* TimeStats consumers; never run silently.
 */
internal object SurfaceActivityDiagnostics {
    private const val TIMEOUT_MS = 16_000L
    private const val MAX_SECONDS = 10
    private const val PACKAGE_COMMAND = "pm list packages | head -n 2500"
    private const val TIMESTATS_START = "dumpsys SurfaceFlinger --timestats -clear -enable | head -c 512"
    private const val TIMESTATS_READ =
        "dumpsys SurfaceFlinger --timestats -dump -maxlayers 128 | head -c 262144"
    private const val TIMESTATS_STOP = "dumpsys SurfaceFlinger --timestats -disable | head -c 512"
    private const val CPU_COMMAND =
        "pid=$(pidof surfaceflinger | cut -d' ' -f1); " +
        "if [ -n \"$pid\" ]; then cat /proc/$pid/stat 2>/dev/null; fi; " +
        "printf '__HZ__\\n'; getconf CLK_TCK 2>/dev/null"
    private const val PROCESSOR_CHECK = "command -v trace_processor_shell 2>/dev/null"
    private val lock = Mutex()
    private val layerKey = Regex("""^(layerName|packageName|totalFrames|droppedFrames|averageFPS)\s*=\s*(.*)$""")
    private val shellPackage = Regex("""[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+""")

    internal data class LayerStats(
        val packageName: String, val frames: Long, val dropped: Long, val fps: Double?
    )
    internal data class TraceRow(val packageName: String, val frames: Long, val jank: Long)

    suspend fun activity(seconds: Int = 5, limit: Int = 15): JSONObject =
        sample(seconds, limit, usePerfetto = false)

    suspend fun perfetto(seconds: Int = 5, limit: Int = 15): JSONObject =
        sample(seconds, limit, usePerfetto = true)

    private suspend fun sample(seconds: Int, limit: Int, usePerfetto: Boolean): JSONObject {
        require(seconds in 3..MAX_SECONDS && limit in 1..50)
        check(AdbCoordinator.verified.value) { "ADB shell UID 2000 is not verified" }
        check(lock.tryLock()) { "A SurfaceFlinger analysis is already running" }
        var started = false
        try {
            val catalog = SurfaceLayersDiagnostics.parsePackages(
                AdbTransport.exec(PACKAGE_COMMAND, timeoutMs = TIMEOUT_MS).getOrThrow())
            if (catalog.isEmpty()) return response("package_catalog_unavailable")
            val first = AdbTransport.exec(TIMESTATS_START, timeoutMs = TIMEOUT_MS).getOrThrow()
            if (denied(first)) return response("permission_denied")
            started = true
            val before = readCpuStat(AdbTransport.exec(CPU_COMMAND, timeoutMs = TIMEOUT_MS).getOrThrow())
            val begin = SystemClock.elapsedRealtime()
            var traceRows: List<TraceRow> = emptyList()
            var tracedCpuMs: Double? = null
            var traceStatus = if (usePerfetto) "processor_unavailable" else "not_requested"
            if (usePerfetto) {
                val bin = AdbTransport.exec(PROCESSOR_CHECK, timeoutMs = TIMEOUT_MS)
                    .getOrThrow().trim()
                if (bin == "trace_processor_shell" || bin == "/system/bin/trace_processor_shell" ||
                    bin == "/vendor/bin/trace_processor_shell" ||
                    bin == "/data/local/tmp/trace_processor_shell") {
                    val trace = runTrace(seconds, bin, catalog)
                    traceRows = trace.first
                    tracedCpuMs = trace.second
                    traceStatus = trace.third
                } else delay(seconds * 1000L)
            } else delay(seconds * 1000L)
            val elapsed = (SystemClock.elapsedRealtime() - begin).coerceAtLeast(1L)
            val after = readCpuStat(AdbTransport.exec(CPU_COMMAND, timeoutMs = TIMEOUT_MS).getOrThrow())
            val raw = AdbTransport.exec(TIMESTATS_READ, timeoutMs = TIMEOUT_MS).getOrThrow()
            val layers = parseTimeStats(raw, catalog)
            val cpu = processCpu(before, after, elapsed)
            val result = summarize(layers, traceRows, elapsed, cpu, limit)
            result.put("perfetto_status", traceStatus)
                .put("perfetto_compositor_sched_cpu_ms", tracedCpuMs ?: JSONObject.NULL)
                .put("sampling_seconds_requested", seconds)
                .put("taken_at", System.currentTimeMillis())
                .put("notes", "Explicit bounded capture. SurfaceFlinger CPU is shared across " +
                    "all layers; ranked packages are rendering contributors, NOT per-app " +
                    "compositor CPU percentages. TimeStats -clear/-enable briefly " +
                    "resets device-wide TimeStats; -disable is called after the capture. " +
                    "Raw layer names, window titles and Perfetto traces never leave the phone.")
            return result
        } finally {
            if (started) withContext(NonCancellable) {
                runCatching { AdbTransport.exec(TIMESTATS_STOP, timeoutMs = TIMEOUT_MS) }
            }
            lock.unlock()
        }
    }

    private fun response(status: String): JSONObject = JSONObject()
        .put("status", status)
        .put("notes", "SurfaceFlinger analysis is unavailable; no per-app CPU attribution is inferred.")

    private fun denied(value: String) =
        value.contains("Permission Denial", ignoreCase = true) ||
            value.contains("Permission denied", ignoreCase = true)

    /** Never reflect raw layerName or packageName unless validated against pm. */
    internal fun parseTimeStats(raw: String, known: Set<String>): List<LayerStats> {
        if (denied(raw)) return emptyList()
        data class MutableLayer(var name: String = "", var pkg: String = "",
            var frames: Long = 0L, var dropped: Long = 0L, var fps: Double? = null)
        val found = mutableListOf<LayerStats>()
        var current: MutableLayer? = null
        fun flush() {
            val layer = current ?: return
            val name = layer.pkg.takeIf { it in known }
                ?: shellPackage.findAll(layer.name).map { it.value }.firstOrNull { it in known }
            if (name != null && layer.frames in 0..100000000L)
                found += LayerStats(name, layer.frames, layer.dropped.coerceAtLeast(0), layer.fps)
        }
        for (line in raw.lineSequence().take(10000)) {
            val match = layerKey.matchEntire(line.trim()) ?: continue
            val key = match.groupValues[1]
            val value = match.groupValues[2].take(200)
            if (key == "layerName") {
                flush()
                current = MutableLayer(name = value)
                continue
            }
            val layer = current ?: continue
            when (key) {
                "packageName" -> layer.pkg = value
                "totalFrames" -> layer.frames = value.toLongOrNull() ?: 0
                "droppedFrames" -> layer.dropped = value.toLongOrNull() ?: 0
                "averageFPS" -> layer.fps = value.toDoubleOrNull()?.takeIf {
                    it.isFinite() && it in 0.0..1000.0
                }
            }
        }
        flush()
        return found.take(200)
    }

    /** /proc/PID/stat fields 14 and 15 (utime + stime), accounting for parentheses. */
    internal fun readCpuStat(raw: String): Pair<Long, Long>? {
        val lines = raw.lineSequence().toList()
        val stat = lines.firstOrNull { it.contains(" (surfaceflinger)") } ?: return null
        val end = stat.lastIndexOf(')')
        if (end <= 0) return null
        val values = stat.substring(end + 1).trim().split(Regex("""\s+"""))
        if (values.size < 13) return null
        val user = values[11].toLongOrNull() ?: return null
        val system = values[12].toLongOrNull() ?: return null
        val hz = lines.indexOf("__HZ__").takeIf { it >= 0 }?.let {
            lines.getOrNull(it + 1)?.trim()?.toLongOrNull()
        }?.takeIf { it in 1..10000 } ?: return null
        return (user + system) to hz
    }

    internal fun processCpu(start: Pair<Long, Long>?, end: Pair<Long, Long>?,
                            elapsedMs: Long): Double? {
        if (start == null || end == null || start.second != end.second ||
            end.first < start.first || elapsedMs <= 0) return null
        val value = (end.first - start.first).toDouble() * 100000.0 /
            (start.second * elapsedMs)
        return rounded(value.takeIf { it.isFinite() && it <= 10000 } ?: return null)
    }

    /** Package scores reflect activity only. CPU load is reported once per session. */
    internal fun summarize(layers: List<LayerStats>, trace: List<TraceRow>,
                           elapsedMs: Long, cpu: Double?, limit: Int): JSONObject {
        data class Row(var frames: Long = 0, var dropped: Long = 0, var layers: Int = 0,
                       var perfettoFrames: Long = 0, var janky: Long = 0)
        val groups = mutableMapOf<String, Row>()
        layers.forEach {
            val record = groups.getOrPut(it.packageName) { Row() }
            record.frames += it.frames
            record.dropped += it.dropped
            record.layers++
        }
        trace.forEach {
            val record = groups.getOrPut(it.packageName) { Row() }
            record.perfettoFrames += it.frames
            record.janky += it.jank
        }
        val rows = JSONArray()
        groups.entries.sortedWith(compareByDescending<Map.Entry<String, Row>> {
            maxOf(it.value.frames, it.value.perfettoFrames)
        }.thenBy { it.key }).take(limit.coerceIn(1, 50)).forEach { (pkg, entry) ->
            rows.put(JSONObject().put("package_name", pkg)
                .put("timestats_frames", entry.frames)
                .put("timestats_dropped_frames", entry.dropped)
                .put("timestats_layers", entry.layers)
                .put("perfetto_frame_events", entry.perfettoFrames)
                .put("perfetto_janky_events", entry.janky)
                .put("timestats_frames_per_second",
                    rounded(entry.frames * 1000.0 / elapsedMs)))
        }
        return response(if (groups.isEmpty()) "no_attributed_activity" else "ok")
            .put("sample_elapsed_ms", elapsedMs)
            .put("surfaceflinger_process_cpu_percent", cpu ?: JSONObject.NULL)
            .put("cpu_status", if (cpu == null) "unavailable" else "ok")
            .put("timestats_layer_count", layers.size)
            .put("ranked_contributors", rows)
            .put("rank_basis", "frame counts (TimeStats and/or FrameTimeline); no CPU attribution")
    }

    /**
     * Perfetto output stays in a temporary shell-only trace file and is deleted
     * even on parse errors. No trace content, slices, titles or arbitrary SQL
     * reaches an MCP client.
     */
    private suspend fun runTrace(
        seconds: Int, executable: String, known: Set<String>
    ): Triple<List<TraceRow>, Double?, String> {
        val file = "/data/misc/perfetto-traces/n2k02_sf_" +
            UUID.randomUUID().toString().replace("-", "") + ".perfetto-trace"
        val config = "duration_ms: ${seconds * 1000} " +
            "buffers { size_kb: 4096 fill_policy: RING_BUFFER } " +
            "data_sources { config { name: \"android.surfaceflinger.frametimeline\" } } " +
            "data_sources { config { name: \"linux.ftrace\" ftrace_config { " +
            "ftrace_events: \"sched/sched_switch\" atrace_categories: \"gfx\" } } } " +
            "data_sources { config { name: \"linux.process_stats\" " +
            "process_stats_config { scan_all_processes_on_start: true } } }"
        val traceCommand = "printf '%s\\n' '$config' | " +
            "perfetto -c - --txt -o $file 2>&1 | head -c 2048"
        val frameSql = "SELECT p.name, COUNT(*), " +
            "SUM(CASE WHEN a.jank_type = 'None' THEN 0 ELSE 1 END) " +
            "FROM actual_frame_timeline_slice a JOIN process p ON a.upid=p.upid " +
            "WHERE p.name NOT LIKE '%surfaceflinger%' GROUP BY p.name " +
            "ORDER BY COUNT(*) DESC LIMIT 100"
        val cpuSql = "SELECT ROUND(SUM(s.dur)/1000000.0,1) " +
            "FROM sched s JOIN thread t ON s.utid=t.utid " +
            "JOIN process p ON t.upid=p.upid " +
            "WHERE p.name LIKE '%surfaceflinger%'"
        try {
            val outcome = AdbTransport.exec(traceCommand, timeoutMs = 40_000L).getOrThrow()
            if (denied(outcome) || outcome.contains("Failed to", ignoreCase = true) ||
                outcome.contains("ERROR", ignoreCase = true))
                return Triple(emptyList(), null, "capture_unavailable")
            val size = AdbTransport.exec("stat -c %s $file 2>/dev/null",
                timeoutMs = TIMEOUT_MS).getOrThrow().trim().toLongOrNull()
            if (size == null || size <= 0 || size > 16L * 1024 * 1024)
                return Triple(emptyList(), null, "trace_missing_or_too_large")
            val frameRaw = AdbTransport.exec(
                "$executable -q '$frameSql' $file 2>&1 | head -c 32768",
                timeoutMs = 40_000L).getOrThrow()
            val cpuRaw = AdbTransport.exec(
                "$executable -q '$cpuSql' $file 2>&1 | head -c 2048",
                timeoutMs = 40_000L).getOrThrow()
            val rows = parseTraceRows(frameRaw, known)
            val cpu = cpuRaw.lineSequence().mapNotNull {
                it.trim().trim('"').toDoubleOrNull()
            }.firstOrNull { it >= 0 && it < 100000 }
            return Triple(rows, cpu,
                if (rows.isEmpty() && cpu == null) "processor_unavailable" else "ok")
        } finally {
            withContext(NonCancellable) {
                runCatching { AdbTransport.exec("rm -f $file", timeoutMs = TIMEOUT_MS) }
            }
        }
    }

    /** Parse only validated Android package names and numeric CSV fields. */
    internal fun parseTraceRows(raw: String, installedPackages: Set<String>): List<TraceRow> {
        return raw.lineSequence().take(150).mapNotNull {
            val fields = it.trim().replace("\"", "").split(",")
            if (fields.size < 3) return@mapNotNull null
            val name = fields[0].trim()
            if (name !in installedPackages) return@mapNotNull null
            val frames = fields[1].trim().toLongOrNull() ?: return@mapNotNull null
            val jank = fields[2].trim().toLongOrNull() ?: return@mapNotNull null
            if (frames !in 1..100000000L || jank !in 0..frames) return@mapNotNull null
            TraceRow(name, frames, jank)
        }.toList()
    }

    private fun rounded(value: Double): Double = round(value * 10.0) / 10.0
}
