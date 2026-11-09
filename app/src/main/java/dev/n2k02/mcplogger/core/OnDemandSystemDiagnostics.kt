package dev.n2k02.mcplogger.core

import dev.n2k02.mcplogger.adb.AdbCoordinator
import dev.n2k02.mcplogger.adb.AdbTransport
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit, bounded, read-only ADB snapshots. No live capture, toggles,
 * arbitrary shell strings, content exports, or background monitoring.
 * Only parsed summary fields are returned to remote callers.
 */
internal object OnDemandSystemDiagnostics {
    private val lock = Mutex()
    private const val TIMEOUT_MS = 9_000L
    private val serviceRecord = Regex("""ServiceRecord\{[^\n]{0,220}""")
    private val thermalStatus = Regex("""(?i)(?:Thermal Status|mStatus|status)\s*[:=]\s*(\d{1,2})""")
    private val temp = Regex("""(?i)(?:Temperature\{[^}]{0,200}\}|(?:cpu|gpu|skin|battery)[^\n]{0,120})""")
    private val number = Regex("""(?i)(?:mValue=|value=|temperature=|temp=)\s*(-?\d+(?:\.\d+)?)""")
    private fun outputUnavailable(output: String): Boolean {
        val lower = output.trim().lowercase()
        return lower.isBlank() ||
            lower.startsWith("permission denial") ||
            lower.startsWith("permission denied") ||
            lower.startsWith("securityexception") ||
            lower.startsWith("can't find service") ||
            lower.startsWith("service not found") ||
            lower.startsWith("unknown command")
    }

    private suspend fun snapshot(command: String, parser: (String) -> JSONObject): JSONObject {
        check(AdbCoordinator.verified.value) { "ADB shell UID 2000 not verified" }
        check(lock.tryLock()) { "Another system diagnostic is in progress" }
        try {
            val result = AdbTransport.exec(command, timeoutMs = TIMEOUT_MS).getOrThrow()
            return parser(result).put("taken_at", System.currentTimeMillis())
        } finally { lock.unlock() }
    }

    suspend fun services(pkg: String): JSONObject {
        val name = AppRuntimeDiagnostics.validate(pkg)
        return snapshot("dumpsys activity services $name | head -c 65536") {
            parseServices(name, it)
        }
    }

    suspend fun audio(pkg: String): JSONObject {
        val name = AppRuntimeDiagnostics.validate(pkg)
        return snapshot("dumpsys audio | head -c 131072") { parseAudio(name, it) }
    }

    suspend fun jobs(pkg: String): JSONObject {
        val name = AppRuntimeDiagnostics.validate(pkg)
        return snapshot("dumpsys jobscheduler | head -c 196608") { parseJobs(name, it) }
    }

    suspend fun thermal(): JSONObject =
        snapshot("dumpsys thermalservice | head -c 65536", ::parseThermal)

    /**
     * Service discovery is a capability hint, not proof of dumpsys permissions.
     * Shell access and OEM service exposure are separately checked by each tool.
     */
    suspend fun capabilities(): JSONObject =
        snapshot("dumpsys -l | head -c 32768", ::parseCapabilities)

    internal fun parseCapabilities(output: String): JSONObject {
        val services = output.lineSequence().map { it.trim().lowercase() }
            .filter { it.matches(Regex("[a-z0-9_.-]{2,90}")) }
            .toSet()
        val supported = JSONObject()
        for ((name, service) in listOf(
            "activity_services" to "activity",
            "audio_state" to "audio",
            "jobscheduler" to "jobscheduler",
            "thermal" to "thermalservice",
            "battery" to "battery",
            "power" to "power",
            "surfaceflinger" to "surfaceflinger"
        )) {
            supported.put(name, when {
                outputUnavailable(output) -> "unavailable"
                service in services -> "service_listed_readability_unverified"
                else -> "not_listed"
            })
        }
        return JSONObject().put("status",
            if (outputUnavailable(output)) "unavailable" else "ok")
            .put("services", supported)
            .put("adb_uid", 2000)
            .put("source", "dumpsys -l")
            .put("notes", "Service listing does not verify access to individual dumpsys output. " +
                "No privileged bypass, live sampling or app-private data access is implied.")
    }


    private fun containsPackage(line: String, pkg: String): Boolean {
        var start = line.indexOf(pkg)
        while (start >= 0) {
            val end = start + pkg.length
            val before = start == 0 || !line[start - 1].let { it.isLetterOrDigit() || it == '_' || it == '.' }
            val after = end == line.length || !line[end].let { it.isLetterOrDigit() || it == '_' || it == '.' }
            if (before && after) return true
            start = line.indexOf(pkg, start + 1)
        }
        return false
    }

    internal fun parseServices(pkg: String, output: String): JSONObject {
        AppRuntimeDiagnostics.validate(pkg)
        var records = 0
        var foregroundHints = 0
        var runningHints = 0
        var currentServiceMatches = false
        for (line in output.lineSequence().take(1600)) {
            val text = line.trim()
            if (serviceRecord.containsMatchIn(text)) {
                currentServiceMatches = containsPackage(text, pkg)
                if (currentServiceMatches) records++
            }
            if (!currentServiceMatches) continue
            if (text.contains("isForeground=true", true) ||
                text.contains("foreground=true", true) ||
                text.contains("fgRequired=true", true)) foregroundHints++
            if (text.contains("app=ProcessRecord") || text.contains("executing=true")) runningHints++
        }
        return JSONObject().put("package_name", pkg)
            .put("service_record_matches", records.coerceAtMost(1000))
            .put("foreground_hints", foregroundHints.coerceAtMost(1000))
            .put("running_hints", runningHints.coerceAtMost(1000))
            .put("status", if (outputUnavailable(output)) "unavailable" else "partial_snapshot")
            .put("source", "dumpsys activity services")
            .put("notes", "Only exact-package service record hints are counted. No service " +
                "names or other app data are exported. Absence does not prove no service is running.")
    }

    internal fun parseAudio(pkg: String, output: String): JSONObject {
        AppRuntimeDiagnostics.validate(pkg)
        var focus = 0
        var record = 0
        var playback = 0
        val audioEvidence = mutableSetOf<String>()
        for (line in output.lineSequence().take(2500)) {
            if (!containsPackage(line, pkg)) continue
            // A package name can itself contain "player", "record", etc.
            // Remove only the verified package token before classifying hints.
            val signal = line.replace(pkg, "")
            if (signal.contains("focus", true) || signal.contains("FocusRequester", true)) focus++
            if (signal.contains("record", true) || signal.contains("Recording", true)) record++
            if (signal.contains("playback", true) || signal.contains("player", true)) playback++
            val lower = signal.lowercase()
            if ("focus_gain" in lower || "focus gain" in lower)
                audioEvidence.add("focus_gain_mentioned")
            if ("focus_loss" in lower || "focus loss" in lower)
                audioEvidence.add("focus_loss_mentioned")
            if ("recording=true" in lower || "startrecord" in lower)
                audioEvidence.add("recording_started_mentioned")
            if ("recording=false" in lower || "stoprecord" in lower)
                audioEvidence.add("recording_stopped_mentioned")
            if ("playerstate=started" in lower || "state:started" in lower)
                audioEvidence.add("playback_started_mentioned")
            if ("playerstate=stopped" in lower || "state:stopped" in lower)
                audioEvidence.add("playback_stopped_mentioned")
        }
        return JSONObject().put("package_name", pkg)
            .put("audio_focus_mentions", focus.coerceAtMost(500))
            .put("recording_mentions", record.coerceAtMost(500))
            .put("playback_mentions", playback.coerceAtMost(500))
            .put("audio_evidence", JSONArray(audioEvidence.sorted()))
            .put("status", if (outputUnavailable(output)) "unavailable" else "partial_snapshot")
            .put("interpretation", "historical_or_current_mentions_only")
            .put("focus_state", "unknown")
            .put("recording_state", "unknown")
            .put("playback_state", "unknown")
            .put("source", "dumpsys audio")
            .put("notes", "Counts package-associated audio dump mentions, not active " +
                "stream certainty. No microphone data, audio content, device IDs, " +
                "or other packages' information is returned.")
    }

    internal fun parseJobs(pkg: String, output: String): JSONObject {
        AppRuntimeDiagnostics.validate(pkg)
        var jobs = 0
        var pending = 0
        var running = 0
        var restricted = 0
        var currentJobMatches = false
        val reasons = mutableSetOf<String>()
        for (line in output.lineSequence().take(3500)) {
            val text = line.trim()
            val lower = text.lowercase()
            if ("jobstatus{" in lower || lower.startsWith("job #")) {
                currentJobMatches = containsPackage(text, pkg)
                if (currentJobMatches) jobs++
            }
            if (!currentJobMatches) continue
            if ("pending" in lower || "waiting" in lower) pending++
            if ("running" in lower || "active" in lower) running++
            if ("restricted" in lower || "quota" in lower ||
                "constraint" in lower) restricted++
            if ("quota" in lower) reasons.add("quota_mentioned")
            if ("standby" in lower || "bucket" in lower)
                reasons.add("standby_bucket_mentioned")
            if ("background restriction" in lower || "background_restricted" in lower)
                reasons.add("background_restriction_mentioned")
            if ("constraint" in lower || "unsatisfied" in lower)
                reasons.add("constraint_mentioned")
            if ("unsatisfied" in lower || "not satisfied" in lower) {
                listOf("charging", "idle", "connectivity", "battery_not_low",
                    "storage_not_low", "timing_delay", "deadline").filter {
                    it in lower
                }.forEach { reasons.add("unsatisfied_" + it) }
            }
        }
        return JSONObject().put("package_name", pkg)
            .put("job_record_matches", jobs.coerceAtMost(2000))
            .put("pending_hints", pending.coerceAtMost(2000))
            .put("running_hints", running.coerceAtMost(2000))
            .put("restriction_hints", restricted.coerceAtMost(2000))
            .put("restriction_evidence", JSONArray(reasons.sorted()))
            .put("restriction_evidence_is_not_verdict", true)
            .put("quota_state", "unknown")
            .put("restriction_state", "unknown")
            .put("evidence_coverage", "package-associated job blocks only")
            .put("status", if (outputUnavailable(output)) "unavailable" else "partial_snapshot")
            .put("source", "dumpsys jobscheduler")
            .put("notes", "Counts only lines explicitly naming this package. " +
                "Job restrictions and scheduling sections can span multiple lines, " +
                "so absent matches mean unknown, not unrestricted or idle.")
    }

    internal fun parseThermal(output: String): JSONObject {
        val status = thermalStatus.find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 0..6 }
        val rows = JSONArray()
        for (line in output.lineSequence().take(1600)) {
            val trimmed = line.trim()
            if (!temp.containsMatchIn(trimmed)) continue
            val type = when {
                trimmed.contains("CPU", true) -> "cpu"
                trimmed.contains("GPU", true) -> "gpu"
                trimmed.contains("SKIN", true) -> "skin"
                trimmed.contains("BATTERY", true) -> "battery"
                else -> continue
            }
            val value = number.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.1..180.0 } ?: continue
            if (rows.length() < 16) rows.put(JSONObject()
                .put("type", type).put("temperature_c", value))
        }
        return JSONObject().put("status", if (!outputUnavailable(output) &&
            (status != null || rows.length() > 0))
            "ok" else "unavailable")
            .put("thermal_status_code", status ?: JSONObject.NULL)
            .put("temperatures", rows)
            .put("zero_or_invalid_temperatures_excluded", true)
            .put("source", "dumpsys thermalservice")
            .put("notes", "Thermal status follows Android 0..6 scale when exposed; " +
                "temperature sensors and throttling levels are OEM dependent. Zero and implausible readings are excluded. " +
                "No hardware identifiers or raw dump are returned.")
    }
}
