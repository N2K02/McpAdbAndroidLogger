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
            return summarize(pkg, processes, meminfo, permissions)
                .put("taken_at", System.currentTimeMillis())
                .put("source", "ps, dumpsys meminfo, dumpsys package")
                .put("note", "Sequential point-in-time ADB snapshots. Permission and " +
                    "memory fields may be unavailable on restricted Android builds.")
        } finally {
            lock.unlock()
        }
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
