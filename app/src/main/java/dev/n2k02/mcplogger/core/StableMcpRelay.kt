package dev.n2k02.mcplogger.core

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Optional stable Cloudflare Workers facade for the embedded Quick Tunnel.
 *
 * Workers only stores the current random trycloudflare.com hostname, never
 * Logcat or OAuth credentials. User-supplied DEVICE_TOKEN stays app-private.
 * Registration happens only after cloudflared confirms its edge connection.
 */
object StableMcpRelay {
    private const val PREFS = "mcp_logger"
    private const val ENDPOINT = "stable_relay_mcp_url"
    private const val DEVICE_KEY = "stable_relay_device_key"

    /** A GitHub Pages website is NOT an MCP proxy. Only a deployed Worker works. */
    internal fun validPublicUrl(raw: String): Boolean = try {
        val uri = URI(raw.trim())
        uri.scheme == "https" &&
            uri.host != null && uri.host.lowercase().endsWith(".workers.dev") &&
            uri.host.length > ".workers.dev".length &&
            uri.rawPath == "/mcp" && uri.port == -1 &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawAuthority == uri.host
    } catch (_: Exception) { false }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedUrl(context: Context): String =
        preferences(context).getString(ENDPOINT, "").orEmpty()

    fun savedKey(context: Context): String =
        preferences(context).getString(DEVICE_KEY, "").orEmpty()

    /** The app can use Quick Tunnel without configuring a permanent relay. */
    fun publicMcpUrl(context: Context): String? =
        savedUrl(context).takeIf { validPublicUrl(it) && savedKey(context).length >= 32 }

    /** No network calls or key rotation when the user saves settings. */
    fun save(context: Context, publicUrl: String, deviceKey: String): Boolean {
        val url = publicUrl.trim()
        val key = deviceKey.trim()
        if (url.isEmpty() && key.isEmpty()) {
            preferences(context).edit().remove(ENDPOINT).remove(DEVICE_KEY).apply()
            return true
        }
        if (!validPublicUrl(url) || key.length !in 32..256 ||
            key.any { it.isWhitespace() || it.code < 33 || it.code > 126 }) return false
        preferences(context).edit()
            .putString(ENDPOINT, url)
            .putString(DEVICE_KEY, key)
            .apply()
        return true
    }

    /**
     * Fail closed if the Worker cannot be updated; never claim the stable URL
     * is online when it may point to an old tunnel on another device.
     */
    fun publish(context: Context, quickUrl: String): Boolean {
        val publicUrl = publicMcpUrl(context) ?: return false
        val quick = runCatching { URI(quickUrl) }.getOrNull() ?: return false
        if (quick.scheme != "https" || quick.rawPath != "/mcp" ||
            quick.userInfo != null || quick.port != -1 ||
            quick.rawQuery != null || quick.rawFragment != null ||
            !QuickMcpTunnel.validHostname(quick.host ?: "")) return false
        val connection = (URL(publicUrl.removeSuffix("/mcp") + "/register")
            .openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + savedKey(context))
            val payload = JSONObject().put("mcpUrl", quickUrl).toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200) false
            else JSONObject(connection.inputStream.bufferedReader().use { it.readText().take(512) })
                .optBoolean("ok", false)
        } catch (_: Exception) { false }
        finally { connection.disconnect() }
    }
}
