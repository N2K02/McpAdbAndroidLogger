package dev.n2k02.mcplogger.core

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.URLDecoder
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.json.JSONObject

/** Loopback-only authenticated local MCP endpoint. Owned by LogMonitorService. */
object LocalMcpServer {
    const val PORT = 8765
    const val URL = "http://127.0.0.1:8765/mcp"
    private const val MAX_BODY = 131072
    private val allowedOrigins = setOf("https://claude.ai", "https://chatgpt.com",
        "https://chat.openai.com")
    private val workers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "mcp-local-client").apply { isDaemon = true }
    }
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var active = false
    private val lock = Any()

    fun start(context: Context): Boolean = synchronized(lock) {
        if (active) return@synchronized true
        BridgeClient.initialize(context.applicationContext)
        try {
            val socket = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
            socket.soTimeout = 1500
            listener = socket
            active = true
            MonitorState.localMcp.value = "Local server running"
            Thread({
                while (active) {
                    try {
                        val client = socket.accept()
                        workers.execute { handle(client, context.applicationContext) }
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (_: SocketException) {
                        break
                    } catch (_: Exception) {
                        if (active) MonitorState.localMcp.value = "Listener error"
                    }
                }
            }, "mcp-local-listener").apply { isDaemon = true }.start()
            true
        } catch (_: Exception) {
            MonitorState.localMcp.value = "Port unavailable"
            false
        }
    }

    fun stop() = synchronized(lock) {
        active = false
        runCatching { listener?.close() }
        listener = null
        MonitorState.localMcp.value = "Stopped"
    }

    private fun handle(client: Socket, context: Context) {
        client.use { socket ->
            try {
                socket.soTimeout = 7000
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val requestLine = readLine(input, 4096) ?: return
                val parts = requestLine.split(" ")
                if (parts.size != 3) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val headers = mutableMapOf<String, String>()
                var total = 0
                while (true) {
                    val line = readLine(input, 8192) ?: return
                    total += line.length
                    if (total > 16384) return
                    if (line.isEmpty()) break
                    val index = line.indexOf(':')
                    if (index < 1) return
                    val name = line.substring(0, index).trim().lowercase()
                    if (headers.containsKey(name)) {
                        respond(output, 400, "Duplicate header")
                        return
                    }
                    headers[name] = line.substring(index + 1).trim()
                }
                val oauthMetadata = path.startsWith("/.well-known/oauth-")
                val oauthPath = path.startsWith("/oauth/")
                if (path != "/health" && path != "/mcp" && !oauthMetadata && !oauthPath) {
                    respond(output, 404, "Not found")
                    return
                }
                val origin = headers["origin"]
                if (origin != null && origin !in allowedOrigins &&
                    origin != LocalMcpOAuth.publicOrigin()) {
                    respond(output, 403, "Untrusted Origin")
                    return
                }
                if (path == "/health" && method == "GET") {
                    respond(output, 200, "{\"service\":\"mcp-adb-logger\",\"status\":\"ok\"}",
                        "application/json")
                    return
                }
                if (oauthMetadata && method == "GET") {
                    val result = LocalMcpOAuth.metadata(path)
                    respond(output, result.status, result.body, result.type, result.headers)
                    return
                }
                if (path == "/oauth/authorize" && method == "GET") {
                    val result = LocalMcpOAuth.authorize(context,
                        parseParams(parts[1].substringAfter('?', "")))
                    respond(output, result.status, result.body, result.type, result.headers)
                    return
                }
                if (oauthPath) {
                    val length = headers["content-length"]?.toIntOrNull()
                    if (method != "POST" || length == null || length !in 1..16384 ||
                        headers["transfer-encoding"] != null) {
                        respond(output, 400, "Invalid OAuth request")
                        return
                    }
                    val bytes = readBody(input, length) ?: return
                    val body = String(bytes, StandardCharsets.UTF_8)
                    val result = when (path) {
                        "/oauth/register" -> {
                            val json = runCatching { JSONObject(body) }.getOrNull()
                            if (json == null) LocalMcpOAuth.Reply(400,
                                """{"error":"invalid_client_metadata"}""")
                            else LocalMcpOAuth.register(context, json)
                        }
                        "/oauth/token" -> LocalMcpOAuth.token(context, parseParams(body))
                        "/oauth/approve" -> LocalMcpOAuth.approve(context, parseParams(body))
                        "/oauth/revoke" -> LocalMcpOAuth.revokeToken(context, parseParams(body))
                        else -> LocalMcpOAuth.Reply(404, """{"error":"not_found"}""")
                    }
                    respond(output, result.status, result.body, result.type, result.headers)
                    return
                }
                val authorization = headers["authorization"].orEmpty()
                val actual = if (authorization.startsWith("Bearer "))
                    authorization.removePrefix("Bearer ") else ""
                val expected = BridgeClient.clientToken(context)
                val legacy = expected.isNotBlank() && MessageDigest.isEqual(
                    actual.toByteArray(StandardCharsets.UTF_8),
                    expected.toByteArray(StandardCharsets.UTF_8))
                if (!legacy && !LocalMcpOAuth.validate(context, actual)) {
                    val challenge = LocalMcpOAuth.challenge()
                    respond(output, 401, "Unauthorized", extra = if (challenge != null)
                        mapOf("WWW-Authenticate" to challenge) else emptyMap())
                    return
                }
                if (method != "POST" || path != "/mcp") {
                    respond(output, 405, "Use Streamable HTTP POST")
                    return
                }
                val size = headers["content-length"]?.toIntOrNull()
                if (size == null || size !in 1..MAX_BODY || headers["transfer-encoding"] != null) {
                    respond(output, 413, "Invalid request length")
                    return
                }
                val data = readBody(input, size) ?: return
                val request = try {
                    JSONObject(String(data, StandardCharsets.UTF_8))
                } catch (_: Exception) {
                    respond(output, 200, LocalMcpProtocol.invalidJson().toString(),
                        "application/json")
                    return
                }
                val result = LocalMcpProtocol.execute(request)
                if (result == null) respond(output, 202, "")
                else respond(output, 200, result.toString(), "application/json")
            } catch (_: Exception) {
                // A malformed client must never crash the monitor process.
            }
        }
    }

    private fun readBody(input: BufferedInputStream, length: Int): ByteArray? {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) return null
            read += count
        }
        return bytes
    }

    private fun parseParams(raw: String): Map<String, String> {
        if (raw.length > 16384) return emptyMap()
        val result = mutableMapOf<String,String>()
        for (part in raw.split('&').take(24)) {
            val name = runCatching { URLDecoder.decode(part.substringBefore('='), "UTF-8") }
                .getOrElse { return emptyMap() }
            val value = runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }
                .getOrElse { return emptyMap() }
            if (name.isBlank() || result.containsKey(name)) return emptyMap()
            result[name] = value
        }
        return result
    }

    private fun readLine(input: BufferedInputStream, max: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= max) {
            val value = input.read()
            if (value < 0) return null
            if (value == 10) return bytes.toString("UTF-8").trimEnd('\r')
            bytes.write(value)
        }
        return null
    }

    /**
     * Chrome may send Origin: null for a regular form POST if the page was served
     * with Referrer-Policy: no-referrer. Keep the real Origin on the OAuth consent
     * form without revealing state, challenge or ticket query parameters.
     *
     * All JSON, MCP and diagnostic responses still use no-referrer.
     */
    internal fun referrerPolicy(contentType: String): String =
        if (contentType == "text/html") "strict-origin" else "no-referrer"

    private fun respond(output: BufferedOutputStream, code: Int, text: String,
                        type: String = "text/plain", extra: Map<String,String> = emptyMap()) {
        val reason = when (code) {
            200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            413 -> "Content Too Large"; 302 -> "Found"; 201 -> "Created"
            429 -> "Too Many Requests"; 503 -> "Service Unavailable"; else -> "Error"
        }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val safeHeaders = extra.entries.filter { (key, value) ->
            key.matches(Regex("[A-Za-z-]+")) && !value.contains('\r') && !value.contains('\n')
        }.joinToString("") { it.key + ": " + it.value + "\r\n" }
        val header = "HTTP/1.1 " + code + " " + reason + "\r\n" +
            "Content-Type: " + type + "; charset=utf-8\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: " + referrerPolicy(type) + "\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'\r\n" +
            safeHeaders + "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }
}
