package dev.n2k02.mcplogger.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Strictly bounded, read-only diagnostic filters. Remote clients cannot supply shell
 * fragments, clear buffers, change local UI filters, or start background collectors.
 */
internal object DiagnosticLogcat {
    private val tagName = Regex("[A-Za-z0-9_.-]{1,80}")
    private val priorities = setOf("V", "D", "I", "W", "E", "F", "S")
    private val allowedBuffers = setOf("main", "system", "crash", "events", "radio")
    private val allowedFormats = setOf("time", "threadtime", "epoch")
    private val profiles = linkedMapOf(
        "audio" to listOf("SEQ-Audio:V", "AndroidRuntime:E", "AudioFlinger:W",
            "AudioPolicyManager:W", "AudioTrack:W", "AudioRecord:W", "AudioService:D"),
        "crashes" to listOf("AndroidRuntime:E", "ActivityManager:E", "libc:F", "DEBUG:E"),
        "performance" to listOf("ActivityManager:I", "Choreographer:W",
            "OpenGLRenderer:W", "HWUI:W", "lmkd:W"),
        "battery" to listOf("BatteryService:D", "PowerManagerService:D",
            "DeviceIdleController:D", "JobScheduler:D", "AlarmManager:D"),
        "system" to listOf("AndroidRuntime:E", "ActivityManager:W", "SystemServer:W"),
    )
    fun catalog(): JSONObject = JSONObject().apply {
        (profiles + DiagnosticProfileStore.all()).forEach { (name, tags) ->
            put(name, JSONArray(tags))
        }
    }
    fun profileNames(): Set<String> = profiles.keys

    data class Options(
        val tags: List<Pair<String, String>>,
        val buffers: List<String>,
        val format: String,
        val mode: String,
        val since: Long?,
        val pid: String?,
    ) {
        /** Safe argv for the device-side ADB executor, never a shell string. */
        fun argv(): List<String> = buildList {
            add("logcat")
            add("-d")
            add("-v")
            add(format)
            buffers.forEach { add("-b"); add(it) }
            if (pid != null) { add("--pid"); add(pid) }
            if (since != null) { add("-T"); add("@"+(since / 1000)) }
            if (tags.isNotEmpty()) {
                add("-s")
                tags.forEach { (tag, priority) -> add("$tag:$priority") }
                add("*:S")
            }
        }
    }

    /** The LogFox parser requires epoch and uid, regardless of display preference. */
    fun captureCommand(options: Options?): String {
        if (options == null) return "logcat -b main -b system -b crash -v uid -v epoch -d -t 2000"
        val buffers = options.buffers.ifEmpty { listOf("main", "system", "crash") }
        return buildList {
            add("logcat")
            buffers.forEach { add("-b"); add(it) }
            addAll(listOf("-v", "uid", "-v", "epoch", "-d", "-t", "2000"))
            if (options.pid != null) { add("--pid"); add(options.pid) }
            if (options.tags.isNotEmpty()) {
                add("-s")
                options.tags.forEach { (tag, priority) -> add("$tag:$priority") }
                add("*:S")
            }
        }.joinToString(" ")
    }

    fun parse(args: JSONObject): Options {
        val profile = args.optString("profile", "").lowercase()
        require(profile.isEmpty() || profile in profiles ||
            DiagnosticProfileStore.get(profile) != null)
        val tags = mutableListOf<String>()
        tags.addAll(profiles[profile] ?: DiagnosticProfileStore.get(profile).orEmpty())
        if (args.has("tags")) {
            val array = args.optJSONArray("tags") ?: throw IllegalArgumentException("tags")
            require(array.length() <= 32)
            for (i in 0 until array.length()) tags.add(array.getString(i))
        }
        require(tags.size <= 48)
        val parsed = tags.map { value ->
            val parts = value.split(":")
            require(parts.size == 2 && tagName.matches(parts[0]) &&
                parts[1].uppercase() in priorities && parts[0] != "*")
            parts[0] to parts[1].uppercase()
        }.distinctBy { it.first }
        val buffers = mutableListOf<String>()
        if (args.has("buffers")) {
            val array = args.optJSONArray("buffers") ?: throw IllegalArgumentException("buffers")
            require(array.length() in 1..5)
            for (i in 0 until array.length()) {
                val buffer = array.getString(i)
                require(buffer in allowedBuffers)
                buffers.add(buffer)
            }
        }
        val format = args.optString("format", "threadtime")
        require(format in allowedFormats)
        val mode = args.optString("mode", "snapshot")
        // Continuous monitoring is intentionally not remotely startable.
        require(mode == "snapshot")
        val since = if (args.has("since")) {
            require(args.opt("since") is Number)
            val value = args.optLong("since", -1)
            require(value >= 0)
            value
        } else null
        val pid = if (args.has("pid")) {
            require(args.opt("pid") is Number)
            val value = args.optInt("pid", -1)
            require(value > 0)
            value.toString()
        } else null
        return Options(parsed, buffers.distinct(), format, mode, since, pid)
    }

    fun select(events: List<LogEvent>, options: Options): List<LogEvent> =
        events.filter { event ->
            (options.since == null || event.epochMillis >= options.since) &&
            (options.pid == null || event.pid == options.pid) &&
            (options.tags.isEmpty() || options.tags.any { (tag, priority) ->
                event.tag == tag && priority != "S" &&
                    severity(event.level) >= severity(priority)
            })
        }

    private fun severity(level: String): Int = when (level.uppercase()) {
        "V" -> 0; "D" -> 1; "I" -> 2; "W" -> 3
        "E" -> 4; "F" -> 5; else -> 6
    }
}
