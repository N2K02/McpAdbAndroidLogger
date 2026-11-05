package dev.n2k02.mcplogger.core

import android.os.SystemClock
import dev.n2k02.mcplogger.adb.AdbCoordinator
import dev.n2k02.mcplogger.adb.AdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

/**
 * Read-only device-health diagnostics. Every ADB command is a fixed literal;
 * no caller can submit shell syntax, tags or file paths. There is no timer,
 * background sampler, persistent capture, or battery-statistics reset.
 */
internal object DeviceDiagnostics {
    private const val TIMEOUT_MS = 15_000L
    private const val BATTERY_COMMAND = "dumpsys battery"
    private const val MEMORY_COMMAND = "cat /proc/meminfo; echo __MCP_PSI__; cat /proc/pressure/memory 2>&1"
    // Keep aggregate 'awl' records: newer Android versions emit these per-UID
    // partial wakelock durations independently of per-tag 'wl' rows.
    // Checkin fields are machine-readable; all tag names are discarded.
    private const val STATS_COMMAND =
        "dumpsys batterystats --charged --checkin | grep -E ',(uid|wl|awl|pwi|bt|lv),' | head -c 524288"
    private const val POWER_COMMAND =
        "dumpsys power | grep -E 'mWakefulness=|Wake Locks:|PARTIAL_WAKE_LOCK' | head -n 120"
    private val lock = Mutex()
    private val uidRegex = Regex("""\buid=(\d{1,7})\b""")
    private val kvRegex = Regex("""^([A-Za-z_][A-Za-z_0-9()]*):\s*(\d+)\s*kB$""")
    private val psiRegex = Regex("""(some|full)\s+avg10=([0-9.]+)\s+avg60=([0-9.]+)\s+avg300=([0-9.]+)\s+total=(\d+)""")
    @Volatile private var baseline: Baseline? = null

    private data class Baseline(val takenAt: Long, val batteryLevel: Int?,
                                val stats: BatteryStats)

    internal data class BatteryStats(
        val packages: Map<Int, String>,
        val partialWakelockMs: Map<Int, Long>,
        val estimatedMah: Map<Int, Double>,
        val screenOffRealtimeMs: Long?,
        val batteryRealtimeMs: Long?,
        val startClockMs: Long?
    )

    private suspend fun snapshot(block: suspend () -> JSONObject): JSONObject {
        check(AdbCoordinator.verified.value) { "ADB shell UID 2000 is not verified" }
        check(lock.tryLock()) { "A health diagnostic is already running" }
        try { return block() } finally { lock.unlock() }
    }

    private suspend fun command(cmd: String): String =
        AdbTransport.exec(cmd, timeoutMs = TIMEOUT_MS).getOrThrow()

    suspend fun memory(): JSONObject = snapshot {
        val raw = command(MEMORY_COMMAND)
        withContext(Dispatchers.Default) { parseMemory(raw) }
            .put("taken_at", System.currentTimeMillis())
            .put("source", "/proc/meminfo and /proc/pressure/memory")
    }

    suspend fun battery(): JSONObject = snapshot {
        val raw = command(BATTERY_COMMAND)
        val stats = command(STATS_COMMAND)
        withContext(Dispatchers.Default) {
            val state = parseBattery(raw)
            val checkin = parseCheckin(stats)
            state.put("estimated_power_since_charge", powerList(checkin, 20))
                .put("power_status", if (checkin.estimatedMah.isEmpty()) "unavailable" else "ok")
                .put("notes", "Battery power estimates are cumulative since charging, not current per-app watts. " +
                    "The owner of a shared Android UID cannot always be uniquely attributed.")
        }.put("taken_at", System.currentTimeMillis())
    }

    suspend fun wakelocks(limit: Int = 20): JSONObject = snapshot {
        val power = command(POWER_COMMAND)
        val raw = command(STATS_COMMAND)
        withContext(Dispatchers.Default) {
            parseWakelocks(power, parseCheckin(raw), limit.coerceIn(1, 50))
        }.put("taken_at", System.currentTimeMillis())
    }

    suspend fun beginScreenOff(): JSONObject = snapshot {
        val battery = parseBattery(command(BATTERY_COMMAND))
        val stats = withContext(Dispatchers.Default) { parseCheckin(command(STATS_COMMAND)) }
        val now = SystemClock.elapsedRealtime()
        baseline = Baseline(now, if (battery.isNull("level_percent")) null
            else battery.getInt("level_percent"), stats)
        JSONObject().put("status", "baseline_saved")
            .put("taken_at", System.currentTimeMillis())
            .put("battery_level_percent", battery.opt("level_percent"))
            .put("instructions", "Turn the screen off, leave the phone idle, then call " +
                "get_screen_off_report. Nothing is sampled between requests. " +
                "The baseline is lost on service stop or app process restart.")
    }

    suspend fun reportScreenOff(limit: Int = 20): JSONObject = snapshot {
        val before = baseline ?: return@snapshot JSONObject()
            .put("status", "no_baseline")
            .put("notes", "Call get_screen_off_baseline first.")
        val end = withContext(Dispatchers.Default) { parseCheckin(command(STATS_COMMAND)) }
        val battery = parseBattery(command(BATTERY_COMMAND))
        val elapsed = (SystemClock.elapsedRealtime() - before.takenAt).coerceAtLeast(0)
        withContext(Dispatchers.Default) {
            screenOffDelta(before.stats, end, elapsed, limit.coerceIn(1, 50))
        }.put("battery_level_start_percent", before.batteryLevel ?: JSONObject.NULL)
            .put("battery_level_end_percent", battery.opt("level_percent"))
            .put("taken_at", System.currentTimeMillis())
    }

    /** Clear on Quit/Stop. Never retain diagnostic baselines in storage. */
    fun clearBaseline() { baseline = null }

    internal fun parseMemory(text: String): JSONObject {
        val mem = mutableMapOf<String, Long>()
        val psi = JSONObject()
        var inPsi = false
        var psiFailure: String? = null
        for (line in text.lineSequence()) {
            if (line.trim() == "__MCP_PSI__") { inPsi = true; continue }
            if (!inPsi) {
                val m = kvRegex.matchEntire(line.trim()) ?: continue
                mem[m.groupValues[1]] = m.groupValues[2].toLongOrNull() ?: continue
            } else {
                // Preserve a safe reason code without leaking raw shell output.
                if (line.contains("Permission denied", ignoreCase = true) ||
                    line.contains("Operation not permitted", ignoreCase = true))
                    psiFailure = "permission_denied"
                else if (line.contains("No such file", ignoreCase = true) ||
                    line.contains("not found", ignoreCase = true))
                    psiFailure = "not_supported"
                val m = psiRegex.find(line) ?: continue
                psi.put(m.groupValues[1], JSONObject()
                    .put("avg10_percent", m.groupValues[2].toDoubleOrNull() ?: JSONObject.NULL)
                    .put("avg60_percent", m.groupValues[3].toDoubleOrNull() ?: JSONObject.NULL)
                    .put("avg300_percent", m.groupValues[4].toDoubleOrNull() ?: JSONObject.NULL)
                    .put("total_stall_microseconds", m.groupValues[5].toLongOrNull() ?: JSONObject.NULL))
            }
        }
        val available = mem["MemAvailable"]
        val total = mem["MemTotal"]
        val swapTotal = mem["SwapTotal"]
        val swapFree = mem["SwapFree"]
        return JSONObject()
            .put("status", if (total == null) "unavailable" else "ok")
            .put("total_ram_kib", total ?: JSONObject.NULL)
            .put("available_ram_kib", available ?: JSONObject.NULL)
            .put("available_ram_percent", if (available != null && total != null && total > 0)
                rounded(available * 100.0 / total) else JSONObject.NULL)
            .put("swap_total_kib", swapTotal ?: JSONObject.NULL)
            .put("swap_used_kib", if (swapTotal != null && swapFree != null)
                (swapTotal - swapFree).coerceAtLeast(0) else JSONObject.NULL)
            .put("psi", psi)
            .put("psi_status", if (psi.length() > 0) "ok" else
                psiFailure ?: "unavailable_or_unrecognized")
            .put("notes", "PSI requires readable /proc/pressure/memory. If blocked by " +
                "Android/SELinux the read is reported as unavailable rather than zero. " +
                "RAM and swap remain readable from /proc/meminfo; neither alone proves " +
                "memory-pressure stalls. PSI percentages represent memory stalls.")
    }

    internal fun parseBattery(text: String): JSONObject {
        val numeric = mutableMapOf<String, Int>()
        val flags = mutableMapOf<String, Boolean>()
        for (line in text.lineSequence()) {
            val pair = line.trim().split(':', limit = 2)
            if (pair.size != 2) continue
            val key = pair[0].trim()
            val value = pair[1].trim()
            value.toIntOrNull()?.let { numeric[key] = it }
            if (value.equals("true", true) || value.equals("false", true))
                flags[key] = value.equals("true", true)
        }
        val level = numeric["level"]
        val scale = numeric["scale"]
        val temp = numeric["temperature"]
        val plugged = numeric["plugged"]
        return JSONObject().put("status", if (level == null) "unavailable" else "ok")
            .put("level_percent", if (level != null && scale != null && scale > 0)
                rounded(level * 100.0 / scale) else level ?: JSONObject.NULL)
            .put("temperature_c", temp?.let { rounded(it / 10.0) } ?: JSONObject.NULL)
            .put("voltage_mv", numeric["voltage"] ?: JSONObject.NULL)
            .put("health_code", numeric["health"] ?: JSONObject.NULL)
            .put("battery_status_code", numeric["status"] ?: JSONObject.NULL)
            .put("plugged_code", plugged ?: JSONObject.NULL)
            .put("is_plugged", if (plugged != null) plugged != 0 else {
                val states = flags.filterKeys { it.endsWith("powered") }.values
                if (states.isEmpty()) JSONObject.NULL else states.any { it }
            })
            .put("source", "dumpsys battery")
    }

    /** Parses only known, numerical checkin fields; never echoes raw wake-lock tags. */
    internal fun parseCheckin(text: String): BatteryStats {
        val packages = mutableMapOf<Int, String>()
        val locks = mutableMapOf<Int, Long>()
        val aggregatedLocks = mutableMapOf<Int, Long>()
        val estimated = mutableMapOf<Int, Double>()
        var screenOff: Long? = null
        var batteryRealtime: Long? = null
        var clock: Long? = null
        for (line in text.lineSequence()) {
            val p = line.trim().split(',', limit = 32)
            if (p.size < 5 || p[0].toIntOrNull() == null) continue
            val uid = p[1].toIntOrNull() ?: continue
            when (p[3]) {
                "uid" -> if (p[2] == "i" && p.size >= 6) {
                    val id = p[4].toIntOrNull() ?: continue
                    val pkg = p[5].trim()
                    if (PerformanceDiagnostics.validPackage(pkg)) {
                        val current = packages[id]
                        if (current == null) packages[id] = pkg
                        else if (current != pkg) packages[id] = "uid:$id (shared)"
                    }
                }
                // Modern AOSP "awl": version,uid,mode,awl,partial_ms,bg_partial_ms.
                // This is the safest per-UID total: tag times can overlap.
                "awl" -> if (p[2] == "l" && uid > 0 && p.size >= 6) {
                    val millis = p[4].toLongOrNull() ?: continue
                    if (millis in 0..1_000_000_000_000L)
                        aggregatedLocks[uid] = millis
                }
                // Per-tag "wl" format evolved: modern rows include three extra
                // duration columns before the 'p' marker. Never assume p[9].
                // Partial total time is immediately BEFORE the 'p' marker in
                // both layouts, not the earlier full-wakelock duration.
                "wl" -> if (p[2] == "l" && uid > 0 && p.size >= 11) {
                    val partialIndex = (5 until p.size).firstOrNull { p[it] == "p" }
                        ?: continue
                    if (partialIndex <= 5 || partialIndex + 1 >= p.size ||
                        p[partialIndex + 1].toIntOrNull() == null) continue
                    val millis = p[partialIndex - 1].toLongOrNull() ?: continue
                    if (millis in 0..1_000_000_000_000L)
                        locks[uid] = (locks[uid] ?: 0L) + millis
                }
                "pwi" -> if (p[2] == "l" && p.size >= 7 && p[4] == "uid") {
                    val id = p[5].toIntOrNull() ?: continue
                    val mah = p[6].toDoubleOrNull() ?: continue
                    if (id > 0 && mah.isFinite() && mah >= 0 && mah < 1e7)
                        estimated[id] = mah
                }
                "bt" -> if (p[2] == "l" && uid == 0 && p.size >= 12) {
                    // Checkin fields: startCount, batteryRealtime, batteryUptime,
                    // totalRealtime, totalUptime, startClock, screenOffRealtime.
                    batteryRealtime = p[5].toLongOrNull()?.takeIf { it >= 0 }
                    clock = p[9].toLongOrNull()?.takeIf { it > 0 }
                    screenOff = p[10].toLongOrNull()?.takeIf { it >= 0 }
                }
            }
        }
        // Prefer aggregated counters when present; tag-level sums are only a
        // fallback and must not be added to the aggregated value.
        return BatteryStats(packages, locks + aggregatedLocks, estimated,
            screenOff, batteryRealtime, clock)
    }

    private fun label(uid: Int, stats: BatteryStats): String =
        stats.packages[uid] ?: "uid:$uid"

    private fun powerList(stats: BatteryStats, limit: Int): JSONArray = JSONArray().apply {
        stats.estimatedMah.entries.sortedByDescending { it.value }.take(limit).forEach { (uid, mah) ->
            put(JSONObject().put("uid", uid).put("package_or_uid", label(uid, stats))
                .put("estimated_mah_since_charge", rounded(mah)))
        }
    }

    /** A held wake lock is not proof of a CPU-spinning app or total CPU usage. */
    internal fun parseWakelocks(power: String, stats: BatteryStats, limit: Int = 20): JSONObject {
        val active = mutableMapOf<Int, Int>()
        for (line in power.lineSequence()) {
            if (!line.contains("PARTIAL_WAKE_LOCK")) continue
            val uid = uidRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            active[uid] = (active[uid] ?: 0) + 1
        }
        val all = (active.keys + stats.partialWakelockMs.keys).distinct().sortedWith(
            compareByDescending<Int> { stats.partialWakelockMs[it] ?: 0 }
                .thenByDescending { active[it] ?: 0 })
        val rows = JSONArray()
        all.take(limit.coerceIn(1, 50)).forEach { uid ->
            rows.put(JSONObject().put("uid", uid)
                .put("package_or_uid", label(uid, stats))
                .put("active_partial_count", active[uid] ?: 0)
                .put("partial_hold_ms_since_charge",
                    stats.partialWakelockMs[uid] ?: JSONObject.NULL))
        }
        return JSONObject().put("status", if (all.isEmpty()) "unavailable_or_none" else "ok")
            .put("wakelocks", rows).put("active_partial_total", active.values.sum())
            .put("cumulative_counter_status", if (stats.partialWakelockMs.isEmpty())
                "unavailable_or_none" else "ok")
            .put("source", "dumpsys power + batterystats --charged --checkin")
            .put("notes", "Active PARTIAL wakelocks are instantaneous and may be hidden " +
                "by the device's power-service dump. Per-UID aggregate hold times are " +
                "preferred when available, otherwise per-tag totals (which can overlap). " +
                "They do not equal CPU time or prove an app kept the screen off. " +
                "Raw wakelock tags are not exposed.")
    }

    internal fun screenOffDelta(start: BatteryStats, end: BatteryStats,
                                elapsedMs: Long, limit: Int = 20): JSONObject {
        val reset = (start.startClockMs != null && end.startClockMs != null &&
                start.startClockMs != end.startClockMs) ||
            (start.batteryRealtimeMs != null && end.batteryRealtimeMs != null &&
                end.batteryRealtimeMs < start.batteryRealtimeMs) ||
            (start.screenOffRealtimeMs != null && end.screenOffRealtimeMs != null &&
                end.screenOffRealtimeMs < start.screenOffRealtimeMs) ||
            start.partialWakelockMs.any { (uid, ms) ->
                (end.partialWakelockMs[uid] ?: 0) < ms
            }
        if (reset) return JSONObject().put("status", "counters_reset")
            .put("notes", "Battery statistics reset between snapshots. Start a new baseline.")
        val rows = JSONArray()
        val deltas = end.partialWakelockMs.mapNotNull { (uid, total) ->
            val diff = total - (start.partialWakelockMs[uid] ?: 0)
            if (diff > 0) uid to diff else null
        }.sortedByDescending { it.second }.take(limit.coerceIn(1, 50))
        deltas.forEach { (uid, ms) ->
            rows.put(JSONObject().put("uid", uid)
                .put("package_or_uid", label(uid, end))
                .put("partial_hold_ms_delta", ms))
        }
        val off = if (start.screenOffRealtimeMs != null && end.screenOffRealtimeMs != null)
            (end.screenOffRealtimeMs - start.screenOffRealtimeMs).coerceAtLeast(0)
        else null
        return JSONObject().put("status", "ok")
            .put("elapsed_ms", elapsedMs)
            .put("screen_off_ms_delta", off ?: JSONObject.NULL)
            .put("wakelock_deltas", rows)
            .put("notes", "Two on-demand cumulative counter reads; no background sampling. " +
                "The screen-off duration is reported by Android batterystats, not inferred " +
                "from an assumed idle interval. Overlapping lock times can exceed elapsed time. " +
                "A missing screen-off counter is unavailable, not zero.")
    }

    private fun rounded(value: Double): Double = round(value * 10.0) / 10.0
}
