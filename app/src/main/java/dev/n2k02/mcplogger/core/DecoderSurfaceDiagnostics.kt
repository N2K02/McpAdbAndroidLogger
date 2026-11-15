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
    // Brief and threadtime formats both preserve the emitting PID. This is a
    // correlation *hint* (PIDs can be recycled in historical Logcat).
    private val briefLine = Regex("""^\\s*[VDIWEF]/([^:( ]{1,80})\\s*\\(\\s*(\\d+)\\):\\s*(.*)$""")
    private val threadtimeLine = Regex("""^\\s*\\d\\d-\\d\\d\\s+\\d\\d:\\d\\d:\\d\\d(?:\\.\\d+)?\\s+(\\d+)\\s+\\d+\\s+[VDIWEF]\\s+([^: ]{1,80}):\\s*(.*)$""")
    private val epochLine = Regex("""^\\s*\\d+(?:\\.\\d+)?\\s+\\d+\\s+(\\d+)\\s+\\d+\\s+[VDIWEF]\\s+([^: ]{1,80}):\\s*(.*)$""")
    private fun isChromiumRenderer(suffix: String): Boolean =
        suffix.split(':').any { it.startsWith("sandboxed_process", true) ||
            it.startsWith("renderer", true) }
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
            "logcat -d -t 700 -v threadtime *:V | tail -c 131072",
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
        val attributablePids = mutableSetOf<Int>()
        for (line in cpuOutput.lineSequence().take(260)) {
            val match = row.matchEntire(line) ?: continue
            val pid = match.groupValues[1].toIntOrNull() ?: continue
            val cpu = match.groupValues[2].toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..10000.0 } ?: continue
            val process = match.groupValues[4]
            if (process != packageName && !process.startsWith(packageName + ":")) continue
            attributablePids.add(pid)
            val item = JSONObject().put("pid", pid).put("cpu_percent", cpu)
            when {
                process == packageName -> { main.put(item); mainCpu += cpu }
                isChromiumRenderer(process.substringAfter(':')) -> {
                    renderers.put(item); rendererCpu += cpu
                }
                else -> { other.put(item); otherCpu += cpu }
            }
        }
        val counts = JSONObject()
        keywords.forEach { (name, _) -> counts.put(name, 0) }
        var matchedLines = 0
        var explicitlyAttributed = 0
        var pidCorrelated = 0
        var unlinkedCodecLines = 0
        // Do not attribute unrelated system-UID MediaCodec messages to the app.
        // An emitting PID from 'top' gives a bounded historical hint, not proof.
        val packageToken = Regex("(?<![A-Za-z0-9_.])" +
            Regex.escape(packageName) + "(?![A-Za-z0-9_.])")
        for (line in logOutput.lineSequence().take(1500)) {
            val brief = briefLine.matchEntire(line)
            val thread = if (brief == null) threadtimeLine.matchEntire(line) else null
            val epoch = if (brief == null && thread == null) epochLine.matchEntire(line) else null
            val pid = (brief?.groupValues?.get(2) ?: thread?.groupValues?.get(1)
                ?: epoch?.groupValues?.get(1))?.toIntOrNull()
            val tag = brief?.groupValues?.get(1) ?: thread?.groupValues?.get(2)
                ?: epoch?.groupValues?.get(2) ?: continue
            if (tag !in permittedTags && !tag.startsWith("cr_")) continue
            val message = (brief?.groupValues?.get(3) ?: thread?.groupValues?.get(3)
                ?: epoch?.groupValues?.get(3) ?: "").take(600)
            val explicit = packageToken.containsMatchIn(message) || packageToken.containsMatchIn(line)
            val byPid = pid != null && pid in attributablePids
            if (!explicit && !byPid) {
                if (tag in setOf("MediaCodec", "ACodec", "CCodec", "Codec2Client")) {
                    unlinkedCodecLines++
                }
                continue
            }
            var observed = false
            keywords.forEach { (name, regex) ->
                if (regex.containsMatchIn(message)) {
                    counts.put(name, counts.getInt(name) + 1)
                    observed = true
                }
            }
            if (observed) {
                matchedLines++
                if (explicit) explicitlyAttributed++ else pidCorrelated++
            }
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
            .put("explicit_package_lifecycle_lines", explicitlyAttributed)
            .put("pid_correlated_lifecycle_lines", pidCorrelated)
            .put("unattributed_codec_log_lines", unlinkedCodecLines)
            .put("decoder_state", "unknown")
            .put("surface_state", "unknown")
            .put("hardware_decoder_confirmed", false)
            .put("notes", "CPU is a single top sample, not a sustained average; percentages " +
                "may exceed 100 on multicore hardware. Only main and explicitly identifiable " +
                "package:*:sandboxed_process* or package:*:renderer* processes are attributed. " +
                "Selected-tag lifecycle hints require an exact package reference or an " +
                "emitting PID observed in top; historical PID reuse remains possible. " +
                "Unattributed system codec lines are counted separately, never assigned " +
                "to this app. Missing events mean unknown. The two reads are " +
                "sequential, not simultaneous. Decoder hardware selection cannot be proven " +
                "from these signals.")
    }
}
