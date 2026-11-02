package dev.n2k02.mcplogger.core

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.URLDecoder
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.RejectedExecutionException
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
    private val clients = LocalMcpSocketRegistry()

    fun start(context: Context): Boolean = synchronized(lock) {
        if (active && listener?.isClosed == false) return@synchronized true
        active = false
        clients.stop()
        runCatching { listener?.close() }
        listener = null
        BridgeClient.initialize(context.applicationContext)
        try {
            val socket = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
            socket.soTimeout = 1500
            listener = socket
            val generation = clients.begin()
            active = true
            MonitorState.localMcp.value = "Local server running"
            Thread({
                while (clients.isCurrent(generation)) {
                    try {
                        val client = socket.accept()
                        if (!clients.register(generation, client)) continue
                        try {
                            workers.execute {
                                try {
                                    if (clients.isCurrent(generation))
                                        handle(client, context.applicationContext)
                                } finally {
                                    runCatching { client.close() }
                                    clients.release(client)
                                }
                            }
                        } catch (_: RejectedExecutionException) {
                            runCatching { client.close() }
                            clients.release(client)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Recheck generation.
                    } catch (_: SocketException) {
                        break
                    } catch (_: Exception) {
                        if (clients.isCurrent(generation))
                            MonitorState.localMcp.value = "Listener error"
                    }
                }
            }, "mcp-local-listener").apply { isDaemon = true }.start()
            true
        } catch (_: Exception) {
            active = false
            clients.stop()
            runCatching { listener?.close() }
            listener = null
            MonitorState.localMcp.value = "Port unavailable"
            false
        }
    }

    fun stop() = synchronized(lock) {
        active = false
        clients.stop()
        runCatching { listener?.close() }
        listener = null
        MonitorState.localMcp.value = "Stopped"
    }

    /** Never log OAuth parameters, headers, browser forms, access tokens or codes. */
    private fun auditOAuth(context: Context, method: String, path: String,
                           origin: String?, userAgent: String?,
                           reply: LocalMcpOAuth.Reply) {
        val json = runCatching { JSONObject(reply.body) }.getOrNull()
        val code = (if (reply.auditCode.isNotBlank()) reply.auditCode
            else json?.optString("error").orEmpty()).take(64)
        val detail = (if (reply.auditReason.isNotBlank()) reply.auditReason
            else json?.optString("error_description").orEmpty()).take(160)
        val success = reply.status in 200..399
        McpAuditStore.record(context,
            McpAuditStore.Agent("oauth-flow",
                McpAuditPolicy.clientHint(origin, userAgent), "OAuth identity not verified"),
            "[OAuth request parameters withheld]",
            if (success) "OAuth step completed" else "OAuth step rejected" +
                (if (code.isNotBlank()) ": " + code else ""),
            "OAuth", path.substringAfterLast('/'),
            if (reply.auditCode == "duplicate_approval") "Accepted"
                else if (success) "OK" else "Denied", 0,
            httpMethod = method, endpoint = path, httpStatus = reply.status,
            reason = detail.ifBlank {
                if (success) "" else code.ifBlank { "OAuth request rejected" }
            })
    }

    /** HTTP authorization schemes are case-insensitive (RFC 7235).
     * Reject malformed credentials without ever persisting their raw contents.
     */
    internal fun bearerToken(header: String): String {
        val separator = header.indexOf(' ')
        if (separator < 1 || !header.substring(0,separator).equals("Bearer", true))
            return ""
        val token = header.substring(separator + 1)
        return if (token.length in 20..256 && token.none { it.isWhitespace() }) token else ""
    }

    /** Storage failures must not result in a silent dropped HTTP connection. */
    private fun safeOAuth(block: () -> LocalMcpOAuth.Reply): LocalMcpOAuth.Reply =
        try {
            block()
        } catch (_: Exception) {
            LocalMcpOAuth.Reply(503,
                """{"error":"temporarily_unavailable","error_description":"OAuth storage unavailable; retry later"}""")
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
                    if (path == "/mcp") McpAuditStore.recordDenied(context, method, path,
                        clientHint = McpAuditPolicy.clientHint(origin, headers["user-agent"]),
                        httpStatus = 403)
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
                    auditOAuth(context, method, path, origin, headers["user-agent"], result)
                    respond(output, result.status, result.body, result.type, result.headers)
                    return
                }
                if (path == "/oauth/consent.js" && method == "GET") {
                    // Same-origin consent enhancement, never logs credential contents.
                    respond(output, 200, LocalMcpOAuth.CONSENT_SCRIPT,
                        "application/javascript")
                    return
                }
                if (path == "/oauth/authorize" && method == "GET") {
                    val result = safeOAuth { LocalMcpOAuth.authorize(context,
                        parseParams(parts[1].substringAfter('?', ""))) }
                    auditOAuth(context, method, path, origin, headers["user-agent"], result)
                    respond(output, result.status, result.body, result.type, result.headers)
                    return
                }
                if (oauthPath) {
                    val length = headers["content-length"]?.toIntOrNull()
                    if (method != "POST" || length == null || length !in 1..16384 ||
                        headers["transfer-encoding"] != null) {
                        val result = LocalMcpOAuth.Reply(400,
                            """{"error":"invalid_request","error_description":"Invalid OAuth method or body framing"}""")
                        auditOAuth(context, method, path, origin, headers["user-agent"], result)
                        respond(output, result.status, result.body, result.type, result.headers)
                        return
                    }
                    val bytes = readBody(input, length) ?: return
                    val body = String(bytes, StandardCharsets.UTF_8)
                    val result = safeOAuth { when (path) {
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
                    } }
                    auditOAuth(context, method, path, origin, headers["user-agent"], result)
                    respond(output, result.status, result.body, result.type, result.headers)
                    return
                }
                val authorization = headers["authorization"].orEmpty()
                val actual = bearerToken(authorization)
                val expected = BridgeClient.clientToken(context)
                val legacy = expected.isNotBlank() && MessageDigest.isEqual(
                    actual.toByteArray(StandardCharsets.UTF_8),
                    expected.toByteArray(StandardCharsets.UTF_8))
                if (!legacy && !LocalMcpOAuth.validate(context, actual)) {
                    // Denials are counted without storing any untrusted payload,
                    // supplied credentials, access token, cookies or headers.
                    if (path == "/mcp") McpAuditStore.recordDenied(context, method, path,
                        hasAuthorization = authorization.isNotBlank(),
                        clientHint = McpAuditPolicy.clientHint(origin, headers["user-agent"]))
                    val challenge = LocalMcpOAuth.challenge()
                    respond(output, 401, "Unauthorized", extra = if (challenge != null)
                        mapOf("WWW-Authenticate" to challenge) else emptyMap())
                    return
                }
                val agent = if (legacy) McpAuditStore.DIRECT
                    else LocalMcpOAuth.clientForToken(context, actual)?.let {
                        McpAuditStore.Agent(it.id, it.name, it.domain)
                    } ?: McpAuditStore.Agent("unknown-oauth", "Unknown OAuth agent", "Expired or revoked")
                if (method != "POST" || path != "/mcp") {
                    respond(output, 405, "Use Streamable HTTP POST",
                        extra = mapOf("Allow" to "POST"))
                    McpAuditStore.record(context, agent, "[request body not read]",
                        "HTTP 405 Method Not Allowed", "MCP", "", "Error", 0,
                        httpMethod = method, endpoint = path, httpStatus = 405,
                        reason = "Streamable HTTP requires POST /mcp")
                    return
                }
                val size = headers["content-length"]?.toIntOrNull()
                if (size == null || size !in 1..MAX_BODY || headers["transfer-encoding"] != null) {
                    respond(output, 413, "Invalid request length")
                    McpAuditStore.record(context, agent, "[request body not read]",
                        "HTTP 413 Payload Too Large", "MCP", "", "Error", 0,
                        httpMethod = method, endpoint = path, httpStatus = 413,
                        reason = "Invalid body framing or size")
                    return
                }
                val data = readBody(input, size) ?: return
                val request = try {
                    JSONObject(String(data, StandardCharsets.UTF_8))
                } catch (_: Exception) {
                    val invalidResponse = LocalMcpProtocol.invalidJson().toString()
                    respond(output, 200, invalidResponse, "application/json")
                    McpAuditStore.record(context, agent, String(data, StandardCharsets.UTF_8),
                        invalidResponse, "invalid_json", "", "Error", 0,
                        httpMethod = method, endpoint = path, httpStatus = 200,
                        reason = "Malformed JSON-RPC request")
                    return
                }
                val started = System.nanoTime()
                // Capability is checked for every request, not just at OAuth login.
                // A cached remote tool name cannot bypass a disabled permission.
                val auditReader: (() -> List<McpAuditEntry>)? =
                    if (McpAgentAuditAccess.enabled(context)) {
                        { if (McpAgentAuditAccess.enabled(context))
                            McpAuditStore.readForAgent(context) else emptyList() }
                    } else null
                val result = LocalMcpProtocol.execute(request, auditReader,
                    logsReader = {
                        // The authenticated MCP worker thread may block while
                        // one bounded shell read executes; no background polling.
                        kotlinx.coroutines.runBlocking { LogMonitorService.readOnDemand() }
                    },
                    appsReader = {
                        kotlinx.coroutines.runBlocking { LogMonitorService.refreshInstalledApps() }
                    },
                    performanceReader = { name, arguments ->
                        // Authenticated requests only; no caller-defined commands.
                        kotlinx.coroutines.runBlocking {
                            val pkg = arguments.optString("package_name")
                            val budget = arguments.optDouble("frame_budget_ms", 16.67)
                            when (name) {
                                "get_cpu_usage" -> PerformanceDiagnostics.cpu(
                                    pkg, arguments.optInt("limit", 20))
                                "get_frame_timing" -> PerformanceDiagnostics.frames(pkg, budget)
                                "get_performance_snapshot" -> PerformanceDiagnostics.summary(pkg, budget)
                                else -> throw IllegalArgumentException()
                            }
                        }
                    })
                val status = if (result == null) "Accepted"
                    else if (result.has("error")) "Error" else "OK"
                val responseBody = result?.toString().orEmpty()
                if (result == null) respond(output, 202, "")
                else respond(output, 200, responseBody, "application/json")
                // Capture the same response payload prepared for and written to
                // this authenticated MCP connection. No HTTP/OAuth headers saved.
                // Prevent audit-history replies from recursively embedding themselves
                // into the private journal or growing on each successive query.
                val auditHistoryRead = request.optString("method") == "tools/call" &&
                    request.optJSONObject("params")?.optString("name") == "get_mcp_audits"
                val auditResponse = if (auditHistoryRead)
                    "[MCP audit-history response omitted; read operation recorded]"
                    else responseBody
                McpAuditStore.record(context, agent,
                    String(data, StandardCharsets.UTF_8),
                    auditResponse, request.optString("method"),
                    if (request.optString("method") == "tools/call")
                        request.optJSONObject("params")?.optString("name").orEmpty()
                    else request.optString("method"),
                    status, (System.nanoTime() - started) / 1_000_000,
                    httpMethod = method, endpoint = path,
                    httpStatus = if (result == null) 202 else 200)
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

    /** A callback-specific CSP is allowed only on the consent HTML response.
     * Other requests retain the globally restrictive form-action 'self'.
     */
    internal fun responseSecurityPolicy(type: String, extra: Map<String,String>): String {
        val fallback = "default-src 'none'; " +
            (if (type == "text/html") "script-src 'self'; " else "") +
            "style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
        return if (type == "text/html") extra["Content-Security-Policy"] ?: fallback
            else fallback
    }

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
            !key.equals("Content-Security-Policy", true) &&
                key.matches(Regex("[A-Za-z-]+")) &&
                !value.contains('\r') && !value.contains('\n')
        }.joinToString("") { it.key + ": " + it.value + "\r\n" }
        val policy = responseSecurityPolicy(type, extra)
        val header = "HTTP/1.1 " + code + " " + reason + "\r\n" +
            "Content-Type: " + type + "; charset=utf-8\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: " + referrerPolicy(type) + "\r\n" +
            "Content-Security-Policy: " + policy + "\r\n" +
            safeHeaders + "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }
}
