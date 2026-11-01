package dev.n2k02.mcplogger.core

import dev.n2k02.mcplogger.adb.AdbCoordinator
import dev.n2k02.mcplogger.adb.AdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round
import kotlin.math.ceil

/** Explicit bounded read-only shell performance snapshots; never background polling. */
internal object PerformanceDiagnostics {
    private val lock = Mutex()
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val whitespace = Regex("\\s+")
    private const val CPU_COMMAND = "top -b -n 1 -m 200 -o PID,%CPU,RES,NAME"
    private const val TIMEOUT_MS = 12_000L

    fun validPackage(name: String): Boolean =
        name.length in 3..150 && packagePattern.matches(name)

    private fun validatePackage(name: String, required: Boolean): String {
        require((name.isBlank() && !required) || validPackage(name)) {
            "Expected an exact Android package name"
        }
        return name
    }

    private fun validateBudget(budget: Double): Double {
        require(budget.isFinite() && budget in 5.0..50.0) { "Invalid frame budget" }
        return budget
    }

    private suspend fun locked(block: suspend () -> JSONObject): JSONObject {
        check(AdbCoordinator.verified.value) { "ADB shell UID 2000 is not verified" }
        check(lock.tryLock()) { "A performance snapshot is already running" }
        try { return block() } finally { lock.unlock() }
    }

    suspend fun cpu(packageName: String = "", limit: Int = 20): JSONObject {
        val pkg = validatePackage(packageName, false)
        return locked { readCpu(pkg, limit.coerceIn(1, 50)) }
    }

    suspend fun frames(packageName: String, frameBudgetMs: Double = 16.67): JSONObject {
        val pkg = validatePackage(packageName, true)
        val budget = validateBudget(frameBudgetMs)
        return locked { readFrames(pkg, budget) }
    }

    suspend fun summary(packageName: String = "", frameBudgetMs: Double = 16.67): JSONObject {
        val pkg = validatePackage(packageName, false)
        val budget = validateBudget(frameBudgetMs)
        return locked {
            JSONObject().put("taken_at", System.currentTimeMillis())
                .put("cpu", readCpu(pkg, 25))
                .put("frames", if (pkg.isBlank()) JSONObject.NULL else readFrames(pkg, budget))
                .put("note", if (pkg.isBlank()) "Supply package_name for app UI frame timing."
                    else "GPU hardware utilization is not available from gfxinfo.")
        }
    }

    private suspend fun readCpu(packageName: String, limit: Int): JSONObject {
        val output = AdbTransport.exec(CPU_COMMAND, timeoutMs = TIMEOUT_MS).getOrThrow()
        return withContext(Dispatchers.Default) {
            parseCpu(output, packageName, limit)
                .put("taken_at", System.currentTimeMillis())
                .put("source", "Android toybox top, single bounded sample")
        }
    }

    private suspend fun readFrames(packageName: String, budget: Double): JSONObject {
        // This command is built only from a validated exact package name.
        val output = AdbTransport.exec(
            "dumpsys gfxinfo " + packageName + " framestats",
            timeoutMs = TIMEOUT_MS
        ).getOrThrow()
        return withContext(Dispatchers.Default) {
            parseFrames(output, packageName, budget)
                .put("taken_at", System.currentTimeMillis())
                .put("source", "Android dumpsys gfxinfo framestats")
        }
    }

    /** Percent may exceed 100 on multicore; neither name nor arguments are user-provided. */
    internal fun parseCpu(text: String, packageName: String = "", limit: Int = 20): JSONObject {
        val rows = text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(whitespace, limit = 4)
            if (parts.size != 4) return@mapNotNull null
            val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
            val cpu = parts[1].removeSuffix("%").toDoubleOrNull() ?: return@mapNotNull null
            if (!cpu.isFinite() || cpu < 0.0 || cpu > 10000.0) return@mapNotNull null
            val resident = parts[2]
            val name = parts[3]
            if (name.length !in 1..150 || resident.length !in 1..20) return@mapNotNull null
            if (name.any { it.isWhitespace() }) return@mapNotNull null
            if (packageName.isNotEmpty() && name != packageName &&
                !name.startsWith(packageName + ":")) return@mapNotNull null
            CpuRow(pid, cpu, resident, name)
        }.sortedByDescending { it.cpu }.take(limit.coerceIn(1, 50)).toList()
        val data = JSONArray()
        rows.forEach { p ->
            data.put(JSONObject().put("pid", p.pid).put("cpu_percent", round1(p.cpu))
                .put("resident_memory", p.resident).put("process", p.name))
        }
        return JSONObject().put("status", if (rows.isEmpty()) "unavailable_or_no_matching_process" else "ok")
            .put("package_name", packageName).put("processes", data)
            .put("notes", "CPU is one short sample, not a sustained average. " +
                "Values may exceed 100 on multicore devices; an absent process may be idle or stopped.")
    }

    private data class CpuRow(val pid: Int, val cpu: Double, val resident: String, val name: String)

    /**
     * UI framestats are not GPU utilization. GPU completion timestamps, when
     * present, include scheduling and are not isolated GPU execution times.
     */
    internal fun parseFrames(text: String, packageName: String, budgetMs: Double = 16.67): JSONObject {
        validateBudget(budgetMs)
        var columns: Map<String, Int>? = null
        // Keep only the recent 240 frame records; dumpsys can contain many thousands.
        val samples = ArrayDeque<Pair<Double, Double?>>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Flags,") && trimmed.contains("IntendedVsync") &&
                trimmed.contains("FrameCompleted")) {
                columns = trimmed.split(",").mapIndexed { i, name -> name.trim() to i }.toMap()
                continue
            }
            val header = columns ?: continue
            if (trimmed.startsWith("---PROFILEDATA---")) {
                columns = null
                continue
            }
            if (trimmed.firstOrNull()?.isDigit() != true) continue
            val values = trimmed.split(",")
            fun number(name: String): Long? =
                header[name]?.let { i -> values.getOrNull(i)?.trim()?.toLongOrNull() }
            if (number("Flags") != 0L) continue
            val vsync = number("IntendedVsync") ?: continue
            val end = number("FrameCompleted") ?: continue
            val delta = end - vsync
            if (delta !in 1L..10_000_000_000L) continue
            val gpu = number("GpuCompleted")
            val submit = number("SwapBuffers")
            val latency = if (gpu != null && submit != null && gpu > submit &&
                gpu - submit <= 10_000_000_000L) (gpu - submit) / 1_000_000.0 else null
            if (samples.size >= 240) samples.removeFirst()
            samples.addLast(delta / 1_000_000.0 to latency)
        }
        val recent = samples.toList()
        val times = recent.map { it.first }.sorted()
        val gpuTimes = recent.mapNotNull { it.second }.sorted()
        val slower = times.count { it > budgetMs }
        return JSONObject()
            .put("status", if (times.isEmpty()) "no_ui_framestats" else "ok")
            .put("package_name", packageName)
            .put("sample_frames", times.size)
            .put("frame_budget_ms", round1(budgetMs))
            .put("slow_frames", slower)
            .put("slow_frames_percent", if (times.isEmpty()) JSONObject.NULL
                else round1(slower * 100.0 / times.size))
            .put("frame_p50_ms", percentile(times, 0.50))
            .put("frame_p95_ms", percentile(times, 0.95))
            .put("frame_p99_ms", percentile(times, 0.99))
            .put("worst_frame_ms", if (times.isEmpty()) JSONObject.NULL else round1(times.last()))
            .put("gpu_completion_latency_p95_ms", percentile(gpuTimes, 0.95))
            .put("gpu_completion_samples", gpuTimes.size)
            .put("notes", "Recent View UI frames only. Slow frames use the given budget " +
                "(default 16.67 ms, a 60 Hz reference). GPU-completion latency is not " +
                "GPU execution time or hardware utilization. SurfaceView/video, " +
                "Vulkan and OpenGL frames may not be captured.")
    }

    private fun percentile(items: List<Double>, fraction: Double): Any =
        if (items.isEmpty()) JSONObject.NULL else
            round1(items[(ceil(items.size * fraction).toInt() - 1).coerceIn(0, items.lastIndex)])

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0
}
