package dev.n2k02.mcplogger.core

import dev.n2k02.mcplogger.adb.AdbCoordinator
import dev.n2k02.mcplogger.adb.AdbTransport
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded diagnostic for a single exact package. Logcat observations are historical
 * lifecycle *hints*, never proof of current codec or Surface state.
 * No raw log messages, URLs, titles, codec buffers, or window names leave this parser.
 */
internal object DecoderSurfaceDiagnostics {
    private const val TIMEOUT_MS = 12_000L
    private val row = Regex("""^\s*(\d+)\s+(\d+(?:\.\d+)?)%?\s+(\S+)\s+(\S+)\s*$""")
    private val logLine = Regex("""^\s*(?:\d\d-\d\d\s+\d\d:\d\d:\d\d(?:\.\d+)?\s+)?(?:\d+\s+){1,2}[VDIWEF]\s+([^: ]{1,80}):\s*(.*)$""")
    private val keywords = listOf(
        "decoder_created" to Regex("""(?i)\b(?:create|configure|allocate|instantiate|start)\w*\b.{0,90}\b(?:decoder|codec)\b|\b(?:decoder|codec)\b.{0,90}\b(?:created|configured|allocated|started)\b"""),
        "decoder_released" to Regex("""(?i)\b(?:release|stop|destroy|flush)\w*\b.{0,90}\b(?:decoder|codec)\b|\b(?:decoder|codec)\b.{0,90}\b(?:released|stopped|destroyed|flushed)\b"""),
        "decoder_error" to Regex("""(?i)\b(?:decoder|codec)\b.{0,100}\b(?:error|failed|exception|timeout)\b"""),
        "surface_created" to Regex("""(?i)\b(?:surfaceCreated|surface created|createSurface|surfaceAvailable)\b"""),
        "surface_destroyed" to Regex("""(?i)\b(?:surfaceDestroyed|surface destroyed|destroySurface|surfaceReleased)\b"""),
        "surface_replaced" to Regex("""(?i)\b(?:setOutputSurface|surfaceChanged|surface changed|surface replacement)\b""")
    )
    private val permittedTags = setOf("MediaCodec", "ACodec", "CCodec", "Codec2Client",
        "SurfaceView", "Surface", "BufferQueueProducer", "BufferQueueConsumer",
        "chromium", "cr_MediaCodec", "cr_VideoFrame", "AndroidRuntime")

    suspend fun capture(packageName: String): JSONObject {
        require(PerformanceDiagnostics.validPackage(packageName)) { "Exact package_name required" }
        check(AdbCoordinator.verified.value) { "ADB shell UID 2000 is not verified" }
        val start = System.currentTimeMillis()
        val cpu = AdbTransport.exec(
            "top -b -n 1 -m 200 -o PID,%CPU,RES,NAME -s 2",
            timeoutMs = TIMEOUT_MS).getOrThrow()
        // Bounded read of the existing circular buffer; no global logcat clearing.
        val logs = AdbTransport.exec(
            "logcat -d -t 700 -v brief *:V | tail -c 131072",
            timeoutMs = TIMEOUT_MS).getOrDefault("")
        return parse(packageName, cpu, logs)
            .put("window_start_ms", start)
            .put("window_end_ms", System.currentTimeMillis())
            .put("source", "sequential Android top and bounded historical logcat")
    }

    internal fun parse(packageName: String, cpuOutput: String, logOutput: String): JSONObject {
        require(PerformanceDiagnostics.validPackage(packageName))
        val main = JSONArray()
        val renderers = JSONArray()
        val other = JSONArray()
        var mainCpu = 0.0
        var rendererCpu = 0.0
        var otherCpu = 0.0
        for (line in cpuOutput.lineSequence().take(260)) {
            val match = row.matchEntire(line) ?: continue
            val pid = match.groupValues[1].toIntOrNull() ?: continue
            val cpu = match.groupValues[2].toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..10000.0 } ?: continue
            val process = match.groupValues[4]
            if (process != packageName && !process.startsWith("$"+"packageName:")) continue
            val item = JSONObject().put("pid", pid).put("cpu_percent", cpu)
            when {
                process == packageName -> { main.put(item); mainCpu += cpu }
                process.substringAfter(':').startsWith("sandboxed_process", true) ||
                    process.substringAfter(':').startsWith("renderer", true) -> {
                    renderers.put(item); rendererCpu += cpu
                }
                else -> { other.put(item); otherCpu += cpu }
            }
        }
        val counts = JSONObject()
        keywords.forEach { (name, _) -> counts.put(name, 0) }
        var matchedLines = 0
        // A system component's codec messages may describe unrelated processes.
        // Only explicit exact-package messages are safely attributable.
        val packageToken = Regex("(?<![A-Za-z0-9_.])" +
            Regex.escape(packageName) + "(?![A-Za-z0-9_.])")
        for (line in logOutput.lineSequence().take(1500)) {
            if (!packageToken.containsMatchIn(line)) continue
            val parsed = logLine.matchEntire(line) ?: continue
            val tag = parsed.groupValues[1]
            if (tag !in permittedTags && !tag.startsWith("cr_")) continue
            val message = parsed.groupValues[2].take(600)
            var observed = false
            keywords.forEach { (name, regex) ->
                if (regex.containsMatchIn(message)) {
                    counts.put(name, counts.getInt(name) + 1)
                    observed = true
                }
            }
            if (observed) matchedLines++
        }
        fun group(rows: JSONArray, cpu: Double): JSONObject = JSONObject()
            .put("processes", rows)
            .put("cpu_percent_sum", kotlin.math.round(cpu * 10) / 10)
            .put("status", if (rows.length() == 0) "not_observed" else "ok")
        return JSONObject()
            .put("status", "partial_snapshot")
            .put("package_name", packageName)
            .put("main_app_cpu", group(main, mainCpu))
            .put("chromium_renderer_cpu", group(renderers, rendererCpu))
            .put("other_app_process_cpu", group(other, otherCpu))
            .put("lifecycle_event_counts", counts)
            .put("attributed_lifecycle_log_lines", matchedLines)
            .put("decoder_state", "unknown")
            .put("surface_state", "unknown")
            .put("hardware_decoder_confirmed", false)
            .put("notes", "CPU is a single top sample, not a sustained average; percentages " +
                "may exceed 100 on multicore hardware. Only main and explicitly identifiable " +
                "package:sandboxed_process* or package:renderer* processes are attributed. " +
                "WebView/Chromium renderer names may differ and may be unobservable. " +
                "Only package-explicit selected-tag logcat messages are counted; missing events " +
                "mean unknown, not that no lifecycle change occurred. The two reads are " +
                "sequential, not simultaneous. Decoder hardware selection cannot be proven " +
                "from these signals.")
    }
}
