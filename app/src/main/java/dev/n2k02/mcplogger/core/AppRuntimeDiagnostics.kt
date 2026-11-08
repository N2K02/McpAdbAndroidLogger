package dev.n2k02.mcplogger.core

import dev.n2k02.mcplogger.adb.AdbCoordinator
import dev.n2k02.mcplogger.adb.AdbTransport
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

/**
 * One explicit, verified-ADB app inventory. Fixed commands only; never returns
 * raw process tables, memory dumps, private paths or arbitrary package dumps.
 */
internal object AppRuntimeDiagnostics {
    private val lock = Mutex()
    private const val TIMEOUT_MS = 12_000L
    private const val PROCESS_COMMAND = "ps -A -o PID,UID,NAME | head -n 1500"
    private val spaces = Regex("\\s+")
    private val androidPermission = Regex("android\\.permission\\.[A-Za-z0-9_.]+")
    private val grantPattern = Regex(
        """(android\.permission\.[A-Za-z0-9_.]+):\s+granted=(true|false)""")

    fun validate(packageName: String): String {
        require(PerformanceDiagnostics.validPackage(packageName))
        return packageName
    }

    suspend fun inspect(packageName: String): JSONObject {
        val pkg = validate(packageName)
        check(AdbCoordinator.verified.value) { "ADB shell UID 2000 is not verified" }
        check(lock.tryLock()) { "An app runtime snapshot is already running" }
        try {
            val processes = AdbTransport.exec(PROCESS_COMMAND, timeoutMs = TIMEOUT_MS)
                .getOrThrow()
            val meminfo = AdbTransport.exec(
                "dumpsys meminfo $pkg | head -c 98304", timeoutMs = TIMEOUT_MS)
                .getOrThrow()
            val permissions = AdbTransport.exec(
                "dumpsys package $pkg | head -c 131072", timeoutMs = TIMEOUT_MS)
                .getOrThrow()
            // A numeric verified UID is mandatory; do not guess from process
            // names or put untrusted strings in an ADB shell command.
            val uid = MonitorState.packageUids.value[pkg]
                ?.takeIf { it.matches(Regex("[0-9]{1,10}")) }
            val uidState = if (uid == null) JSONObject()
                .put("status", "uid_unavailable")
            else {
                val result = AdbTransport.exec("cmd activity get-uid-state $uid",
                    timeoutMs = 4_000)
                result.fold(
                    onSuccess = { parseUidState(uid, it) },
                    onFailure = { JSONObject().put("status", "unavailable") })
            }
            return summarize(pkg, processes, meminfo, permissions)
                .put("uid_state", uidState)
                .put("taken_at", System.currentTimeMillis())
                .put("source", "ps, dumpsys meminfo, dumpsys package")
                .put("note", "Sequential point-in-time ADB snapshots. Permission and " +
                    "memory fields may be unavailable on restricted Android builds.")
        } finally {
            lock.unlock()
        }
    }

    /** Numeric process state only: labels change across Android releases. */
    internal fun parseUidState(uid: String, raw: String): JSONObject {
        require(uid.matches(Regex("[0-9]{1,10}")))
        val state = raw.trim().substringBefore(' ').toIntOrNull()
            ?.takeIf { it in -1..3000 }
        return JSONObject().put("uid", uid)
            .put("status", if (state == null) "unavailable" else "ok")
            .put("state_code", state ?: JSONObject.NULL)
            .put("source", "cmd activity get-uid-state")
            .put("notes", "Numeric UID process state is Android-version dependent; "
                + "do not infer exact foreground-service type from this value.")
    }

    internal fun summarize(packageName: String, processes: String,
        meminfo: String, packageDump: String): JSONObject {
        val pkg = validate(packageName)
        val rows = JSONArray()
        var pidCount = 0
        for (line in processes.lineSequence().take(1501)) {
            val parts = line.trim().split(spaces)
            if (parts.size < 3) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val uid = parts[1]
            val name = parts[2]
            if (pid <= 0 || uid.length !in 1..20 ||
                !uid.matches(Regex("[A-Za-z0-9_]+"))) continue
            if (name != pkg && !name.startsWith("$pkg:")) continue
            pidCount++
            if (pidCount <= 30) {
                rows.put(JSONObject().put("pid", pid).put("uid", uid)
                    .put("process", name.take(150)))
            }
        }
        var totalPss: Long? = null
        for (line in meminfo.lineSequence().take(4000)) {
            val match = Regex("""^\s*TOTAL PSS:\s*(\d+)""").find(line)
            if (match != null) {
                totalPss = match.groupValues[1].toLongOrNull()
                break
            }
        }
        val grants = JSONArray()
        var denied = 0
        for (line in packageDump.lineSequence().take(5000)) {
            val match = grantPattern.find(line) ?: continue
            val permission = match.groupValues[1]
            if (!androidPermission.matches(permission)) continue
            val granted = match.groupValues[2] == "true"
            if (!granted) denied++
            if (grants.length() < 80) {
                grants.put(JSONObject().put("permission", permission)
                    .put("granted", granted))
            }
        }
        return JSONObject().put("package_name", pkg)
            .put("process_status", if (pidCount > 0) "running" else "not_visible_or_stopped")
            .put("process_count", pidCount.coerceAtMost(30))
            .put("processes", rows)
            .put("total_pss_kib", totalPss ?: JSONObject.NULL)
            .put("memory_status", if (totalPss == null) "unavailable" else "ok")
            .put("permissions", grants)
            .put("denied_permissions_in_dump", denied)
            .put("permissions_status",
                if (grants.length() == 0) "unavailable_or_not_listed" else "ok")
    }
}
