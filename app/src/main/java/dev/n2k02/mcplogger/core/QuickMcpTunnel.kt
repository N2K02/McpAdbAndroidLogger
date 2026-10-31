package dev.n2k02.mcplogger.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL

/**
 * Cloudflare Quick Tunnel for Android ARM64. Phone stays behind NAT; cloudflared
 * connects outward to Cloudflare and forwards to the authenticated loopback MCP.
 *
 * Temporary trycloudflare.com URL requires no account and changes each start.
 * Do not claim it is connected until cloudflared confirms an edge registration.
 * No bundled binaries are downloaded at runtime.
 */
object QuickMcpTunnel {
    private const val REGISTER = "https://api.trycloudflare.com/tunnel"
    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var starting = false
    @Volatile private var stopped = false
    @Volatile private var credentials: File? = null
    @Volatile private var config: File? = null

    suspend fun start(context: Context) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (starting || process?.isAlive == true) return@withContext
            starting = true
            stopped = false
        }
        MonitorState.tunnel.value = "Starting secure tunnel..."
        MonitorState.publicMcpUrl.value = ""
        try {
            check(LocalMcpServer.PORT == 8765)
            val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
            if (!binary.exists() || !binary.canExecute()) {
                MonitorState.tunnel.value =
                    "Tunnel binary unavailable on this CPU; local MCP works on localhost"
                return@withContext
            }

            // Java/Android system DNS is required: Linux cloudflared cannot use
            // Android's resolver. Register and pre-resolve edge IPs from Java.
            val registration = register()
            val id = registration.getString("id")
            val hostname = registration.getString("hostname")
            require(hostname.endsWith(".trycloudflare.com") &&
                hostname.matches(Regex("[a-zA-Z0-9.-]+"))) { "Invalid tunnel hostname" }
            val edgeIps = listOf("region1.v2.argotunnel.com",
                "region2.v2.argotunnel.com").flatMap { host ->
                runCatching {
                    InetAddress.getAllByName(host).filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress?.let { addr -> addr + ":7844" } }
                }.getOrDefault(emptyList())
            }.distinct().take(4)
            check(edgeIps.isNotEmpty()) { "Cloudflare edge DNS unavailable" }
            val dir = context.cacheDir
            val secretFile = File(dir, "mcp-quick-tunnel-credentials.json")
            val yamlFile = File(dir, "mcp-quick-tunnel-config.yml")
            secretFile.writeText(JSONObject()
                .put("AccountTag", registration.getString("account_tag"))
                .put("TunnelID", id)
                .put("TunnelSecret", registration.getString("secret")).toString())
            secretFile.setReadable(false, false)
            secretFile.setReadable(true, true)
            secretFile.setWritable(false, false)
            secretFile.setWritable(true, true)
            yamlFile.writeText("tunnel: " + id + "\n" +
                "credentials-file: " + secretFile.absolutePath + "\n" +
                "protocol: http2\n" +
                "ingress:\n  - hostname: " + hostname + "\n" +
                "    service: http://127.0.0.1:" + LocalMcpServer.PORT +
                "\n  - service: http_status:404\n")
            credentials = secretFile
            config = yamlFile
            val args = mutableListOf(binary.absolutePath, "tunnel",
                "--config", yamlFile.absolutePath, "--edge-ip-version", "4",
                "--no-autoupdate")
            edgeIps.forEach { ip -> args.add("--edge"); args.add(ip) }
            args.add("run")
            args.add(id)
            if (stopped) return@withContext
            val running = ProcessBuilder(args)
                .directory(dir).redirectErrorStream(true).start()
            synchronized(lock) {
                if (stopped) running.destroyForcibly()
                else process = running
            }
            if (stopped) return@withContext
            MonitorState.tunnel.value = "Connecting to Cloudflare..."
            // Do not report a random hostname until the edge is actually registered.
            running.inputStream.bufferedReader().use { reader ->
                while (!stopped) {
                    val line = reader.readLine() ?: break
                    if (line.contains("Registered tunnel connection", ignoreCase = true) ||
                        line.contains("Connection registered", ignoreCase = true)) {
                        MonitorState.publicMcpUrl.value = "https://" + hostname + "/mcp"
                        MonitorState.tunnel.value = "Public MCP connected"
                    } else if (line.contains("ERR") || line.contains("error", true)) {
                        // No tokens or logs are forwarded into diagnostics.
                        MonitorState.tunnel.value = "Tunnel is reconnecting"
                    }
                }
            }
            if (!stopped) {
                MonitorState.publicMcpUrl.value = ""
                MonitorState.tunnel.value = "Tunnel disconnected"
            }
        } catch (_: Exception) {
            if (!stopped) {
                MonitorState.publicMcpUrl.value = ""
                MonitorState.tunnel.value = "Tunnel unavailable; local MCP still works"
            }
        } finally {
            synchronized(lock) {
                process?.destroyForcibly()
                process = null
                starting = false
            }
            credentials?.delete()
            config?.delete()
            credentials = null
            config = null
        }
    }

    private fun register(): JSONObject {
        val c = (URL(REGISTER).openConnection() as HttpURLConnection)
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 12000
            c.readTimeout = 12000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", "McpAdbAndroidLogger/1.0")
            c.outputStream.use { it.write(ByteArray(0)) }
            check(c.responseCode in 200..299) { "Tunnel registration rejected" }
            val data = c.inputStream.bufferedReader().use { it.readText().take(16000) }
            val reply = JSONObject(data)
            check(reply.optBoolean("success", false)) { "Cloudflare unavailable" }
            return reply.getJSONObject("result")
        } finally {
            c.disconnect()
        }
    }

    fun stop() {
        synchronized(lock) {
            stopped = true
            process?.destroyForcibly()
            process = null
        }
        credentials?.delete()
        config?.delete()
        MonitorState.publicMcpUrl.value = ""
        MonitorState.tunnel.value = "Stopped"
    }
}
