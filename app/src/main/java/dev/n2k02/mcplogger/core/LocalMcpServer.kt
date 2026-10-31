package dev.n2k02.mcplogger.core

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
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
                if (path != "/health" && path != "/mcp") {
                    respond(output, 404, "Not found")
                    return
                }
                val origin = headers["origin"]
                if (origin != null && origin !in allowedOrigins) {
                    respond(output, 403, "Untrusted Origin")
                    return
                }
                if (path == "/health" && method == "GET") {
                    respond(output, 200, "{\"service\":\"mcp-adb-logger\",\"status\":\"ok\"}",
                        "application/json")
                    return
                }
                val authorization = headers["authorization"].orEmpty()
                val actual = if (authorization.startsWith("Bearer "))
                    authorization.removePrefix("Bearer ") else ""
                val expected = BridgeClient.clientToken(context)
                if (expected.isEmpty() || !MessageDigest.isEqual(
                        actual.toByteArray(StandardCharsets.UTF_8),
                        expected.toByteArray(StandardCharsets.UTF_8))) {
                    respond(output, 401, "Unauthorized")
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
                val data = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    val count = input.read(data, offset, size - offset)
                    if (count < 0) return
                    offset += count
                }
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

    private fun respond(output: BufferedOutputStream, code: Int, text: String,
                        type: String = "text/plain") {
        val reason = when (code) {
            200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            413 -> "Content Too Large"; else -> "Error"
        }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 " + code + " " + reason + "\r\n" +
            "Content-Type: " + type + "; charset=utf-8\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }
}
