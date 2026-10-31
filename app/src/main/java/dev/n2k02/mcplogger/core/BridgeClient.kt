package dev.n2k02.mcplogger.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * User-configured outbound HTTPS transport. Never binds a listening socket on Android.
 * The device API key remains only in app-private preferences and HTTPS headers.
 */
object BridgeClient {
    private fun prefs(context: Context) =
        context.getSharedPreferences("mcp_logger", Context.MODE_PRIVATE)

    /** Read-only diagnostics upload is automatic once a valid HTTPS gateway is configured. */
    fun configured(context: Context): Boolean {
        val p = prefs(context)
        return p.getBoolean("bridge_enabled", false) &&
            McpConnectionSetup.gatewayBaseOrNull(p.getString("gateway", "").orEmpty()) != null &&
            !p.getString("device_key", "").isNullOrBlank()
    }

    /** Generate local secrets once. Never overwrite an existing user/server token. */
    fun initialize(context: Context) {
        val p = prefs(context)
        val key = p.getString("device_key", "").orEmpty()
            .ifBlank { McpConnectionSetup.newSecret() }
        val client = p.getString("mcp_client_token", "").orEmpty()
            .ifBlank { McpConnectionSetup.newSecret() }
        val url = p.getString("gateway_draft", null) ?: p.getString("gateway", "").orEmpty()
        autoSave(context, url, key, client)
    }

    /** Persist even incomplete edits, but never upload without validated HTTPS and key. */
    fun autoSave(context: Context, url: String, key: String, clientToken: String) {
        val base = McpConnectionSetup.gatewayBaseOrNull(url)
        prefs(context).edit()
            .putString("gateway_draft", url)
            .putString("gateway", base.orEmpty())
            .putString("device_key", key)
            .putString("mcp_client_token", clientToken)
            .putBoolean("bridge_enabled", base != null && key.isNotBlank())
            .apply()
        tokenFlow.value = clientToken
        if (base == null) MonitorState.gateway.value = "Gateway not configured"
    }

    fun settings(context: Context): Triple<String, String, Boolean> {
        val p = prefs(context)
        return Triple(
            p.getString("gateway_draft", null) ?: p.getString("gateway", "").orEmpty(),
            p.getString("device_key", "").orEmpty(),
            configured(context),
        )
    }

    fun clientToken(context: Context): String =
        prefs(context).getString("mcp_client_token", "").orEmpty()

    private val tokenFlow = MutableStateFlow<String?>(null)

    /** Observable key so every screen (Home card, MCP page) updates after rotation. */
    fun clientTokenState(context: Context): StateFlow<String?> {
        if (tokenFlow.value == null) tokenFlow.value = clientToken(context)
        return tokenFlow
    }

    fun saveClientToken(context: Context, value: String) {
        prefs(context).edit().putString("mcp_client_token", value).apply()
        tokenFlow.value = value
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
