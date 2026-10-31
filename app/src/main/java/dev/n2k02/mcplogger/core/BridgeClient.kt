package dev.n2k02.mcplogger.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Explicit opt-in outbound HTTPS transport. Never binds a listening socket on Android.
 * The device API key remains only in app-private preferences and HTTPS headers.
 */
object BridgeClient {
    private fun prefs(context: Context) = context.getSharedPreferences("mcp_logger", Context.MODE_PRIVATE)
    fun configured(context: Context): Boolean =
        prefs(context).getBoolean("bridge_enabled", false) &&
        McpConnectionSetup.gatewayBaseOrNull(
            prefs(context).getString("gateway", "").orEmpty()) != null &&
        !prefs(context).getString("device_key", "").isNullOrBlank()

    fun configure(context: Context, url: String, key: String, enabled: Boolean) {
        val normalized = McpConnectionSetup.gatewayBaseOrNull(url)
        require(url.isBlank() || normalized != null) { "Expected an HTTPS gateway URL" }
        require(!enabled || (normalized != null && key.isNotBlank())) {
            "A deployed HTTPS gateway and device key are required"
        }
        prefs(context).edit().putString("gateway", normalized.orEmpty())
            .putString("device_key", key).putBoolean("bridge_enabled", enabled).apply()
    }

    fun clientToken(context: Context): String =
        prefs(context).getString("mcp_client_token", "").orEmpty()

    fun saveClientToken(context: Context, token: String) {
        prefs(context).edit().putString("mcp_client_token", token).apply()
    }

    /** Reachability/identity check only; upload auth is tested on actual upload. */
    suspend fun checkGateway(url: String): Boolean = withContext(Dispatchers.IO) {
        val base = McpConnectionSetup.gatewayBaseOrNull(url) ?: return@withContext false
        try {
            val connection = URL("$base/health").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 7000
                connection.readTimeout = 7000
                connection.useCaches = false
                if (connection.responseCode != 200) return@withContext false
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val result = JSONObject(body)
                result.optString("service") == "mcp-adb-logger" &&
                    result.optString("status") == "ok"
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }
    fun settings(context: Context): Triple<String, String, Boolean> {
        val p = prefs(context)
        return Triple(p.getString("gateway", "") ?: "", p.getString("device_key", "") ?: "",
            p.getBoolean("bridge_enabled", false))
    }
    suspend fun upload(context: Context, events: List<LogEvent>): Boolean = withContext(Dispatchers.IO) {
        if (!configured(context)) return@withContext false
        val p = prefs(context)
        val base = p.getString("gateway", "")!!.trimEnd('/')
        val root = JSONObject()
            .put("device", "android")
            .put("package", MonitorState.selectedPackage.value)
            .put("adb", MonitorState.adb.value)
            .put("packages", JSONArray(MonitorState.packages.value))
        val array = JSONArray()
        events.takeLast(300).forEach { event ->
            array.put(JSONObject().put("id", event.id).put("time", event.epochMillis)
                .put("uid", event.uid).put("pid", event.pid)
                .put("level", event.level).put("tag", SensitiveLogRedactor.redact(event.tag))
                .put("message", SensitiveLogRedactor.redact(event.message)))
        }
        root.put("logs", array)
        try {
            val connection = (URL(base + "/ingest").openConnection() as HttpURLConnection)
            try {
                connection.connectTimeout = 7000
                connection.readTimeout = 7000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Device-Key", p.getString("device_key", "")!!)
                connection.outputStream.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
                val success = connection.responseCode in 200..299
                MonitorState.gateway.value = if (success) "Connected" else "HTTP " + connection.responseCode
                success
            } finally { connection.disconnect() }
        } catch (error: Exception) {
            MonitorState.gateway.value = "Unavailable"
            MonitorState.error("Gateway: " + error.javaClass.simpleName)
            false
        }
    }
}
