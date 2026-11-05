package dev.n2k02.mcplogger.core

import dev.n2k02.mcplogger.adb.AdbCoordinator
import dev.n2k02.mcplogger.adb.AdbTransport
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-shot display-layer inspection. SurfaceFlinger layer names can contain
 * window titles, notification text and other user data, so the raw names must
 * never enter an MCP response, UI result, audit payload or log message.
 *
 * Only exact names from the installed Android package catalog are returned.
 * Layer counts are presence hints, NOT per-app compositor CPU/GPU usage.
 */
internal object SurfaceLayersDiagnostics {
    private const val TIMEOUT_MS = 15_000L
    private const val SURFACE_COMMAND =
        "dumpsys SurfaceFlinger --list | head -c 131072"
    private const val PACKAGES_COMMAND = "pm list packages | head -n 2500"
    private const val MAX_LAYERS = 600
    private val lock = Mutex()
    private val packagePattern = Regex("""[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+){1,12}""")

    suspend fun layers(limit: Int = 20): JSONObject {
        check(AdbCoordinator.verified.value) { "ADB shell UID 2000 is not verified" }
        check(lock.tryLock()) { "A SurfaceFlinger diagnostic is already running" }
        try {
            val raw = AdbTransport.exec(SURFACE_COMMAND, timeoutMs = TIMEOUT_MS).getOrThrow()
            if (isDenied(raw)) return status("permission_denied")
            if (raw.isBlank() || raw.contains("Can't find service", ignoreCase = true))
                return status("unavailable")
            val packages = AdbTransport.exec(PACKAGES_COMMAND, timeoutMs = TIMEOUT_MS)
                .getOrThrow()
            return parseLayers(raw, packages, limit)
                .put("taken_at", System.currentTimeMillis())
        } finally {
            lock.unlock()
        }
    }

    private fun status(reason: String): JSONObject = JSONObject()
        .put("status", reason)
        .put("source", "dumpsys SurfaceFlinger --list")
        .put("notes", "SurfaceFlinger layers are not GPU utilization or per-app CPU. " +
            "Raw window/layer names are never returned.")

    private fun isDenied(value: String): Boolean =
        value.contains("Permission Denial", ignoreCase = true) ||
            value.contains("Permission denied", ignoreCase = true) ||
            value.contains("not allowed to dump", ignoreCase = true)

    /** Parse a bounded device package list, never an unverified layer string. */
    internal fun parsePackages(raw: String): Set<String> = raw.lineSequence()
        .take(2500)
        .mapNotNull { line ->
            line.trim().takeIf { it.startsWith("package:") }
                ?.removePrefix("package:")
                ?.takeIf { PerformanceDiagnostics.validPackage(it) }
        }.toSet()

    /** Pure parser: never reflects untrusted layer contents into JSON. */
    internal fun parseLayers(
        raw: String, installedPackages: String, limit: Int = 20
    ): JSONObject {
        if (isDenied(raw)) return status("permission_denied")
        if (raw.isBlank() || raw.contains("Can't find service", ignoreCase = true))
            return status("unavailable")
        val known = parsePackages(installedPackages)
        if (known.isEmpty()) return status("package_catalog_unavailable")
        val lines = raw.lineSequence().take(MAX_LAYERS + 1).toList()
        val truncated = lines.size > MAX_LAYERS
        val samples = lines.take(MAX_LAYERS)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Total layers:") &&
                !it.startsWith("Layer count:", ignoreCase = true) }
        val totals = mutableMapOf<String, Int>()
        var unattributed = 0
        samples.forEach { layer ->
            val matching = packagePattern.findAll(layer)
                .map { it.value }.filter { it in known }.toSet()
            if (matching.isEmpty()) unattributed++ else
                matching.forEach { pkg -> totals[pkg] = (totals[pkg] ?: 0) + 1 }
        }
        val max = limit.coerceIn(1, 50)
        val rows = JSONArray()
        totals.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
            .thenBy { it.key }).take(max).forEach { (pkg, count) ->
            rows.put(JSONObject().put("package_name", pkg).put("layer_count", count))
        }
        return status(if (samples.isEmpty()) "no_layers" else "ok")
            .put("observed_layer_count", samples.size)
            .put("attributed_package_count", totals.size)
            .put("unattributed_layer_count", unattributed)
            .put("limited_input", truncated)
            .put("packages", rows)
            .put("notes", "One read-only, bounded SurfaceFlinger layer listing. " +
                "Names are matched only to the installed package catalog; all raw " +
                "layer names, window titles and notification contents are discarded. " +
                "A layer may contain multiple package names; counts can overlap. " +
                "Layer presence does not establish which app consumed SurfaceFlinger " +
                "CPU, generated frames, or used the GPU.")
    }
}
