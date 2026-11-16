package dev.n2k02.mcplogger.shizuku

import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService. Runs in a separate shell/root process only after the
 * user explicitly authorizes Shizuku. Exposes fixed, bounded READ operations.
 * Never accepts shell strings, file paths, package names, or raw Binder codes.
 */
class ReadOnlyShizukuService : IReadOnlyDiagnostics.Stub() {
    override fun remoteUid(): Int = Process.myUid()

    override fun read(operation: Int): String {
        check(remoteUid() == 2000 || remoteUid() == 0) { "Unsupported Shizuku UID" }
        if (operation == 4) return serviceAvailability()
        val command = when (operation) {
            1 -> "dumpsys thermalservice | head -c 65536"
            2 -> "dumpsys -l | head -c 32768"
            3 -> "dumpsys jobscheduler | head -c 196608"
            else -> throw IllegalArgumentException("Unknown diagnostic operation")
        }
        return boundedRead(command)
    }

    private fun boundedRead(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val sink = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    var remaining = 196608
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (n <= 0) break
                        sink.write(buffer, 0, n)
                        remaining -= n
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            check(process.waitFor(7, TimeUnit.SECONDS)) { "shizuku_read_timeout" }
            reader.join(500)
            return sink.toString("UTF-8")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            reader.interrupt()
        }
    }

    /**
     * Hidden ServiceManager.checkService, in the isolated authorized process,
     * used only to discover existence. No Binder transactions or bypass.
     * A listed service does not imply DUMP permission or usable interfaces.
     */
    private fun serviceAvailability(): String {
        val names = listOf("activity", "audio", "jobscheduler",
            "thermalservice", "SurfaceFlinger")
        val probe = runCatching {
            val manager = Class.forName("android.os.ServiceManager")
            val check = manager.getMethod("checkService", String::class.java)
            names.associateWith { name ->
                runCatching { check.invoke(null, name) != null }.getOrDefault(false)
            }
        }.getOrNull()
        val result = org.json.JSONObject()
            .put("status", if (probe == null) "unavailable" else "ok")
            .put("source", "authorized_shizuku_user_service_hidden_api_checkService")
            .put("readability", "not_verified")
        if (probe != null) {
            val services = org.json.JSONObject()
            probe.forEach { (name, exists) -> services.put(name, exists) }
            result.put("services", services)
        }
        return result.toString()
    }

    override fun destroy() {
        // Shizuku's reserved destroy Binder transaction.
        kotlin.system.exitProcess(0)
    }
}
