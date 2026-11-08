package dev.n2k02.mcplogger.core

import dev.n2k02.mcplogger.adb.AdbCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * One request = one bounded observation window. No process-wide job, open
 * stream, background worker, or device-side monitoring daemon.
 *
 * ADB shell identity is checked by the existing verified transport. Exact
 * package attribution uses package UID, never a text mention of the package.
 */
internal object LiveAppDiagnostics {
    private const val MAX_SECONDS = 7
    private const val DEADLINE_MS = 10_000L
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    data class Request(val packageName: String, val durationSeconds: Int,
        val limit: Int, val profile: String)

    fun parse(args: JSONObject): Request {
        val packageName = args.optString("package_name").trim()
        require(packageName.length in 3..150 && packagePattern.matches(packageName))
        require(!args.has("duration_seconds") || args.opt("duration_seconds") is Number)
        val duration = args.optInt("duration_seconds", 3)
        // Reserve at least three seconds for the bounded Logcat dump/return.
        require(duration in 1..MAX_SECONDS)
        require(!args.has("limit") || args.opt("limit") is Number)
        val limit = args.optInt("limit", 150)
        require(limit in 1..500)
        val profile = args.optString("profile", "")
        if (profile.isNotEmpty()) {
            DiagnosticLogcat.parse(JSONObject().put("profile", profile))
        }
        return Request(packageName, duration, limit, profile)
    }

    /**
     * Injecting the clock/reader makes exact filters, shutdown and deadlines
     * testable without ADB or network access in unit tests.
     */
    suspend fun observe(request: Request,
        uidLookup: suspend (String) -> String?,
        waitWindow: suspend (Long) -> Unit,
        read: suspend (Long, Long, String) -> List<LogEvent>,
        clock: () -> Long = System::currentTimeMillis): JSONObject =
        withTimeout(DEADLINE_MS) {
            val uid = uidLookup(request.packageName)
                ?.takeIf { it.matches(Regex("[0-9]{1,10}")) }
                ?: throw IllegalArgumentException("Unknown or unverified app UID")
            val startedAt = clock()
            waitWindow(request.durationSeconds * 1000L)
            val logs = read(startedAt, 2_000, request.profile).asSequence()
                .filter { it.uid == uid && it.epochMillis >= startedAt }
                .takeLastBounded(request.limit)
            val endedAt = clock()
            val rows = JSONArray()
            logs.forEach { event ->
                rows.put(JSONObject().put("time", event.epochMillis)
                    .put("uid", event.uid.take(32)).put("pid", event.pid.take(32))
                    .put("level", event.level.take(1))
                    .put("tag", SensitiveLogRedactor.redact(event.tag).take(120))
                    .put("message", SensitiveLogRedactor.redact(event.message).take(2000)))
            }
            JSONObject().put("package_name", request.packageName)
                .put("window_start_ms", startedAt)
                .put("window_end_ms", endedAt)
                .put("requested_seconds", request.durationSeconds)
                .put("mode", "single_on_demand_window")
                .put("log_count", logs.size)
                .put("errors", logs.count { it.level.equals("E", true) ||
                    it.level.equals("F", true) })
                .put("warnings", logs.count { it.level.equals("W", true) })
                .put("events", rows)
                .put("note", "Exact package UID attribution. The result captures only " +
                    "Logcat entries present in the bounded system buffer; some framework " +
                    "events use other UIDs and will not appear.")
        }

    private fun Sequence<LogEvent>.takeLastBounded(limit: Int): List<LogEvent> {
        val result = ArrayDeque<LogEvent>()
        for (event in this) {
            result.addLast(event)
            if (result.size > limit) result.removeFirst()
        }
        return result.toList()
    }

    suspend fun collect(args: JSONObject): JSONObject {
        val req = parse(args)
        check(AdbCoordinator.verified.value) { "ADB UID 2000 not verified" }
        return observe(req,
            uidLookup = { pkg ->
                // Fail closed if cached identity is unavailable. A regular
                // list_installed_apps/log read establishes this mapping.
                MonitorState.packageUids.value[pkg]
            },
            waitWindow = { delay(it) },
            read = { since, timeout, profile ->
                val config = JSONObject().put("since", since)
                if (profile.isNotEmpty()) config.put("profile", profile)
                LogMonitorService.readOnDemand(options = DiagnosticLogcat.parse(config),
                    commandTimeoutMs = timeout)
            })
    }
}
