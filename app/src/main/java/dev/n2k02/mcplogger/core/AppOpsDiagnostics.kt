package dev.n2k02.mcplogger.core

import dev.n2k02.mcplogger.adb.AdbCoordinator
import dev.n2k02.mcplogger.adb.AdbTransport
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only Android AppOps inspection. The command uses only an exact, validated
 * package. Return allowlisted operation modes, never last-access timestamps,
 * proxies, raw AppOps history, shell output or arbitrary operation names.
 */
internal object AppOpsDiagnostics {
    private val lock = Mutex()
    private const val TIMEOUT_MS = 12_000L
    private val allowedOps = setOf(
        "CAMERA", "RECORD_AUDIO", "TAKE_AUDIO_FOCUS", "PLAY_AUDIO",
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
        "ACCESS_BACKGROUND_LOCATION", "POST_NOTIFICATION",
        "SYSTEM_ALERT_WINDOW", "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND",
        "START_FOREGROUND", "SCHEDULE_EXACT_ALARM", "WRITE_SETTINGS",
        "MANAGE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO",
        "READ_MEDIA_AUDIO")
    private val permittedModes = setOf(
        "allow", "deny", "ignore", "default", "foreground", "errored")
    private val row = Regex("""^\s*([A-Z][A-Z0-9_]+):\s*([A-Za-z_]+)""")

    suspend fun inspect(packageName: String): JSONObject {
        val pkg = AppRuntimeDiagnostics.validate(packageName)
        check(AdbCoordinator.verified.value) { "ADB shell UID 2000 is not verified" }
        check(lock.tryLock()) { "An AppOps diagnostic is already running" }
        try {
            val output = AdbTransport.exec("cmd appops get $pkg | head -c 65536",
                timeoutMs = TIMEOUT_MS).getOrThrow()
            return parse(pkg, output).put("taken_at", System.currentTimeMillis())
        } finally { lock.unlock() }
    }

    internal fun parse(packageName: String, output: String): JSONObject {
        val pkg = AppRuntimeDiagnostics.validate(packageName)
        val modes = linkedMapOf<String, String>()
        for (line in output.lineSequence().take(2000)) {
            val match = row.find(line) ?: continue
            val op = match.groupValues[1]
            val mode = match.groupValues[2].lowercase()
            if (op in allowedOps && mode in permittedModes) modes[op] = mode
        }
        val rows = JSONArray()
        modes.toSortedMap().forEach { (op, mode) ->
            rows.put(JSONObject().put("operation", op).put("mode", mode))
        }
        return JSONObject().put("package_name", pkg)
            .put("status", if (rows.length() > 0) "ok" else "unavailable_or_no_explicit_modes")
            .put("operation_count", rows.length())
            .put("operations", rows)
            .put("source", "cmd appops get (read only)")
            .put("notes", "Only selected operation modes are returned. Absent operations " +
                "must not be interpreted as allowed; OEM/Android support varies. " +
                "Timestamps, attribution/proxy details and raw output are discarded.")
    }
}
