package dev.n2k02.mcplogger.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * The trycloudflare.com registration (tunnel ID, secret, hostname) is saved in
 * app-private no-backup storage and reused, so the public URL survives reconnects,
 * network changes, app restarts and reboots. A new URL is only issued when the
 * user rotates it, or when Cloudflare no longer accepts the saved tunnel.
 *
 * Do not claim a URL is live until cloudflared has confirmed an edge registration
 * for that tunnel at least once. No bundled binaries are downloaded at runtime.
 */
object QuickMcpTunnel {
    private const val REGISTER = "https://api.trycloudflare.com/tunnel"
    private const val SAVED = "mcp-quick-tunnel.json"
    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var starting = false
    @Volatile private var stopped = false
    @Volatile private var config: File? = null

    /** Saved registration. [confirmed] once Cloudflare accepted a connection for it. */
    internal data class Registration(
        val id: String, val secret: String, val accountTag: String,
        val hostname: String, val confirmed: Boolean,
    ) {
        val mcpUrl get() = "https://" + hostname + "/mcp"
        fun toJson(): String = JSONObject().put("id", id).put("secret", secret)
            .put("account_tag", accountTag).put("hostname", hostname)
            .put("confirmed", confirmed).toString()
        companion object {
            fun fromJson(text: String): Registration? = runCatching {
                val o = JSONObject(text)
                Registration(o.getString("id"), o.getString("secret"),
                    o.getString("account_tag"), o.getString("hostname"),
                    o.optBoolean("confirmed", false)).takeIf { validHostname(it.hostname) }
            }.getOrNull()
        }
    }

    internal fun validHostname(hostname: String) =
        hostname.endsWith(".trycloudflare.com") && hostname.matches(Regex("[a-zA-Z0-9.-]+"))

    /**
     * Tracks cloudflared's HA connections by connIndex. One failing connection must
     * not mark the tunnel as down while others still serve traffic.
     */
    internal class ConnectionTracker {
        private val live = mutableSetOf<String>()
        var everRegistered = false; private set
        var rejected = false; private set
        val connected get() = live.isNotEmpty()

        /** Returns true when the connected/disconnected state may have changed. */
        fun onLine(line: String): Boolean {
            val index = Regex("connIndex=(\\d+)").find(line)?.groupValues?.get(1) ?: "0"
            return when {
                line.contains("Registered tunnel connection", true) ||
                    line.contains("Connection registered", true) -> {
                    live.add(index); everRegistered = true; true
                }
                line.contains("Unauthorized", true) ||
                    line.contains("Invalid tunnel secret", true) ||
                    line.contains("tunnel not found", true) ||
                    line.contains("TunnelNotFound", true) -> {
                    rejected = true; live.clear(); true
                }
                line.contains("Unregistered tunnel connection", true) ||
                    line.contains("Connection terminated", true) ||
                    line.contains("Serve tunnel error", true) ||
                    line.contains("Retrying connection", true) -> {
                    live.remove(index); true
                }
                else -> false
            }
        }
    }

    private fun savedFile(context: Context) = File(context.noBackupFilesDir, SAVED)

    internal fun loadSaved(context: Context): Registration? =
        savedFile(context).takeIf { it.isFile }?.let { Registration.fromJson(it.readText()) }

    private fun save(context: Context, r: Registration) {
        val f = savedFile(context)
        f.writeText(r.toJson())
        ownerOnly(f)
    }

    /** Forget the saved URL; the next start registers a new one. */
    fun forget(context: Context) {
        savedFile(context).delete()
        File(context.noBackupFilesDir, "mcp-quick-tunnel-credentials.json").delete()
    }

    private fun ownerOnly(f: File) {
        f.setReadable(false, false); f.setReadable(true, true)
        f.setWritable(false, false); f.setWritable(true, true)
    }

    /**
     * Supervises cloudflared until [stop]: restarts it with backoff after it exits
     * or loses all connections, reusing the saved registration each time.
     */
    suspend fun start(context: Context) = withContext(Dispatchers.IO) {
        // A just-stopped supervisor may still be unwinding; let it finish first.
        repeat(30) { if (starting && stopped) delay(100) }
        synchronized(lock) {
            if (starting || process?.isAlive == true) return@withContext
            starting = true
            stopped = false
        }
        MonitorState.tunnel.value = "Starting secure tunnel..."
        MonitorState.publicMcpUrl.value = StableMcpRelay.publicMcpUrl(context).orEmpty()
        var backoff = 2_000L
        var failuresWithoutRegistration = 0
        try {
            check(LocalMcpServer.PORT == 8765)
            val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
            if (!binary.exists() || !binary.canExecute()) {
                MonitorState.tunnel.value =
                    "Tunnel binary unavailable on this CPU; local MCP works on localhost"
                return@withContext
            }
            while (!stopped) {
                val tracker = ConnectionTracker()
                try {
                    var reg = loadSaved(context)
                    if (reg == null) {
                        MonitorState.tunnel.value = "Requesting a new public URL..."
                        val r = register()
                        reg = Registration(r.getString("id"), r.getString("secret"),
                            r.getString("account_tag"), r.getString("hostname"), false)
                        require(validHostname(reg.hostname)) { "Invalid tunnel hostname" }
                        save(context, reg)
                    }
                    // A URL already confirmed earlier is stable: keep showing it while
                    // cloudflared reconnects so the connector settings stay valid.
                    if (reg.confirmed) MonitorState.publicMcpUrl.value =
                        StableMcpRelay.publicMcpUrl(context) ?: reg.mcpUrl
                    runOnce(context, binary, reg, tracker)
                } catch (e: Exception) {
                    if (stopped) break
                }
                if (stopped) break
                if (tracker.rejected) {
                    // Cloudflare dropped the saved quick tunnel; issue a new URL.
                    forget(context)
                    MonitorState.publicMcpUrl.value =
                        StableMcpRelay.publicMcpUrl(context).orEmpty()
                    MonitorState.tunnel.value = "Saved URL expired; requesting a new one..."
                    backoff = 2_000L
                    continue
                }
                if (tracker.everRegistered) {
                    failuresWithoutRegistration = 0
                    backoff = 2_000L
                } else if (++failuresWithoutRegistration >= 4 && loadSaved(context)?.confirmed == false) {
                    // An unconfirmed registration that never connects is discarded.
                    forget(context)
                    failuresWithoutRegistration = 0
                }
                MonitorState.tunnel.value = "Tunnel is reconnecting"
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
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
            config?.delete()
            config = null
        }
    }

    /** Runs cloudflared once; returns when it exits or [stop] is called. */
    private fun runOnce(context: Context, binary: File, reg: Registration,
        tracker: ConnectionTracker) {
        // Java/Android system DNS is required: Linux cloudflared cannot use
        // Android's resolver. Re-resolve every attempt so network changes recover.
        val edgeIps = listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")
            .flatMap { host ->
                runCatching {
                    InetAddress.getAllByName(host).filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress?.let { addr -> addr + ":7844" } }
                        .take(2)
                }.getOrDefault(emptyList())
            }.distinct()
        check(edgeIps.isNotEmpty()) { "Cloudflare edge DNS unavailable" }
        val dir = context.noBackupFilesDir
        val secretFile = File(dir, "mcp-quick-tunnel-credentials.json")
        val yamlFile = File(context.cacheDir, "mcp-quick-tunnel-config.yml")
        secretFile.writeText(JSONObject()
            .put("AccountTag", reg.accountTag)
            .put("TunnelID", reg.id)
            .put("TunnelSecret", reg.secret).toString())
        ownerOnly(secretFile)
        yamlFile.writeText("tunnel: " + reg.id + "\n" +
            "credentials-file: " + secretFile.absolutePath + "\n" +
            "protocol: http2\n" +
            "ingress:\n  - hostname: " + reg.hostname + "\n" +
            "    service: http://127.0.0.1:" + LocalMcpServer.PORT +
            "\n  - service: http_status:404\n")
        config = yamlFile
        val args = mutableListOf(binary.absolutePath, "tunnel",
            "--config", yamlFile.absolutePath, "--edge-ip-version", "4",
            "--no-autoupdate")
        edgeIps.forEach { ip -> args.add("--edge"); args.add(ip) }
        args.add("run")
        args.add(reg.id)
        if (stopped) return
        val running = ProcessBuilder(args)
            .directory(context.cacheDir).redirectErrorStream(true).start()
        synchronized(lock) {
            if (stopped) running.destroyForcibly()
            else process = running
        }
        if (stopped) return
        MonitorState.tunnel.value = "Connecting to Cloudflare..."
        var confirmed = reg.confirmed
        running.inputStream.bufferedReader().use { reader ->
            while (!stopped) {
                val line = reader.readLine() ?: break
                // No tokens or logs are forwarded into status text.
                if (!tracker.onLine(line)) continue
                if (tracker.connected) {
                    if (!confirmed) {
                        confirmed = true
                        save(context, reg.copy(confirmed = true))
                    }
                    val stable = StableMcpRelay.publicMcpUrl(context)
                    val published = stable == null || StableMcpRelay.publish(context, reg.mcpUrl)
                    MonitorState.publicMcpUrl.value = stable ?: reg.mcpUrl
                    MonitorState.tunnel.value = if (published) "Public MCP connected"
                        else "Stable relay unavailable"
                } else if (tracker.rejected) {
                    running.destroyForcibly()
                    break
                } else {
                    MonitorState.tunnel.value = "Tunnel is reconnecting"
                }
            }
        }
        synchronized(lock) {
            running.destroyForcibly()
            if (process === running) process = null
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
        config?.delete()
        MonitorState.publicMcpUrl.value = ""
        MonitorState.tunnel.value = "Stopped"
    }
}
