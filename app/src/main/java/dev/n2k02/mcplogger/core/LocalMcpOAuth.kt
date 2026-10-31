package dev.n2k02.mcplogger.core

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Provider-neutral OAuth authorization server for remote MCP clients.
 *
 * OAuth public clients use dynamic client registration and S256 PKCE. The
 * existing MCP key is used ONLY when approving a browser consent screen.
 * Access/refresh tokens and one-time codes are stored as SHA-256 hashes.
 */
internal object LocalMcpOAuth {
    private const val SCOPE = "logcat:read"
    private const val ACCESS_MS = 60L * 60 * 1000
    private const val REFRESH_MS = 30L * 24 * 60 * 60 * 1000
    private const val CODE_MS = 5L * 60 * 1000
    private const val FILE = "local-mcp-oauth.json"
    private val random = SecureRandom()
    private val lock = Any()
    data class ConnectedClient(val id: String, val name: String, val domain: String)
    val connected = MutableStateFlow<List<ConnectedClient>>(emptyList())
    data class Reply(
        val status: Int, val body: String,
        val type: String = "application/json",
        val headers: Map<String, String> = emptyMap()
    )
    private fun reply(code: Int, data: JSONObject) = Reply(code, data.toString())
    private fun failure(code: String, status: Int = 400) =
        reply(status, JSONObject().put("error", code))
    private fun nonce(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes,
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }
    private fun digest(v: String): String = MessageDigest.getInstance("SHA-256")
        .digest(v.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun same(a: String, b: String) = MessageDigest.isEqual(
        a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))
    private fun url(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun redirect(location: String) =
        Reply(302, "", "text/plain", mapOf("Location" to location))
    private fun withParams(location: String, parameters: Map<String, String>): String =
        location + (if (location.contains("?")) "&" else "?") +
            parameters.entries.joinToString("&") { url(it.key) + "=" + url(it.value) }

    private fun stateFile(c: Context) = File(c.noBackupFilesDir, FILE)
    private fun load(c: Context): JSONObject = runCatching {
        JSONObject(stateFile(c).readText())
    }.getOrElse { JSONObject() }
    private fun save(c: Context, data: JSONObject) {
        val dest = stateFile(c)
        val temp = File(dest.parentFile, dest.name + ".tmp")
        temp.writeText(data.toString())
        temp.setReadable(false, false)
        temp.setReadable(true, true)
        temp.setWritable(false, false)
        temp.setWritable(true, true)
        check(temp.renameTo(dest)) { "OAuth state persistence failed" }
    }
    private fun table(s: JSONObject, name: String): JSONObject =
        s.optJSONObject(name) ?: JSONObject().also { s.put(name, it) }
    private fun prune(s: JSONObject, now: Long) {
        for (name in listOf("pending", "codes", "tokens")) {
            val items = table(s, name)
            for (key in items.keys().asSequence().toList()) {
                if ((items.optJSONObject(key)?.optLong("expiry", 0L) ?: 0L) <= now)
                    items.remove(key)
            }
        }
    }
    private fun syncKey(s: JSONObject, c: Context) {
        // Rotating the MCP key invalidates all OAuth sessions automatically.
        val fingerprint = digest(BridgeClient.clientToken(c))
        if (s.optString("master") != fingerprint) {
            s.put("master", fingerprint)
            s.put("pending", JSONObject())
            s.put("codes", JSONObject())
            s.put("tokens", JSONObject())
        }
    }
    private fun current(c: Context): JSONObject = load(c).also {
        syncKey(it, c)
        prune(it, System.currentTimeMillis())
    }
    private fun updateConnected(s: JSONObject) {
        val clientMap = table(s, "clients")
        val tokens = table(s, "tokens")
        val names = mutableSetOf<String>()
        for (key in tokens.keys()) {
            val t = tokens.optJSONObject(key) ?: continue
            if (t.optLong("expiry") > System.currentTimeMillis()) names += t.optString("client")
        }
        connected.value = names.mapNotNull { id ->
            val entry = clientMap.optJSONObject(id) ?: return@mapNotNull null
            ConnectedClient(id, entry.optString("name"), entry.optString("domain"))
        }.sortedBy { it.name }
    }
    fun refreshState(c: Context) = synchronized(lock) {
        val s = current(c); save(c,s); updateConnected(s)
    }
    private fun issuer(): String? {
        val uri = runCatching { URI(MonitorState.publicMcpUrl.value) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.path != "/mcp" ||
            uri.userInfo != null || uri.rawFragment != null || uri.rawQuery != null)
            return null
        return "https://" + uri.rawAuthority
    }
    fun resource(): String? = issuer()?.plus("/mcp")
    fun publicOrigin() = issuer()
    fun challenge() = issuer()?.let {
        "Bearer resource_metadata=\"" + it +
            "/.well-known/oauth-protected-resource\", scope=\"" + SCOPE + "\""
    }
    fun metadata(path: String): Reply {
        val origin = issuer() ?: return failure("temporarily_unavailable",503)
        return when (path) {
            "/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/mcp" -> reply(200,
                JSONObject().put("resource", origin + "/mcp")
                    .put("authorization_servers", JSONArray().put(origin))
                    .put("scopes_supported", JSONArray().put(SCOPE)))
            "/.well-known/oauth-authorization-server" -> reply(200,
                JSONObject().put("issuer",origin)
                    .put("authorization_endpoint",origin + "/oauth/authorize")
                    .put("token_endpoint",origin + "/oauth/token")
                    .put("registration_endpoint",origin + "/oauth/register")
                    .put("revocation_endpoint",origin + "/oauth/revoke")
                    .put("response_types_supported",JSONArray().put("code"))
                    .put("grant_types_supported",JSONArray().put("authorization_code").put("refresh_token"))
                    .put("token_endpoint_auth_methods_supported",JSONArray().put("none"))
                    .put("code_challenge_methods_supported",JSONArray().put("S256"))
                    .put("authorization_response_iss_parameter_supported",true)
                    .put("scopes_supported",JSONArray().put(SCOPE)))
            else -> failure("not_found",404)
        }
    }
    /** Supports any OAuth-compatible vendor, without a hardcoded provider allowlist.
     * Never fetches arbitrary URLs: avoids SSRF while registering unknown agents.
     */
    internal fun validRedirect(s: String): Boolean = try {
        val u = URI(s)
        val host = u.host?.lowercase().orEmpty()
        val local = host == "127.0.0.1" || host == "localhost" || host == "[::1]"
        u.userInfo == null && u.fragment == null && u.rawQuery == null &&
            u.path.isNotBlank() && u.path.startsWith("/") &&
            u.port in -1..65535 && u.port != 0 &&
            if (u.scheme == "https") host.isNotBlank()
            else u.scheme == "http" && local && u.port in 1..65535
    } catch (_: Exception) { false }

    internal fun pkceMatches(verifier: String, challenge: String): Boolean {
        if (!Regex("[A-Za-z0-9._~-]{43,128}").matches(verifier) ||
            !Regex("[A-Za-z0-9_-]{43,128}").matches(challenge)) return false
        val hashed = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        val value = Base64.encodeToString(hashed,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return same(value,challenge)
    }
    /** A provider-supplied name is never an identity assertion: show the redirect domain too. */
    private fun html(v: String): String = v.replace("&","&amp;").replace("<","&lt;")
        .replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")

    fun register(c: Context, obj: JSONObject): Reply = synchronized(lock) {
        if (issuer() == null) return@synchronized failure("temporarily_unavailable",503)
        val urls = obj.optJSONArray("redirect_uris")
            ?: return@synchronized failure("invalid_redirect_uri")
        if (urls.length() !in 1..5 ||
            obj.optString("token_endpoint_auth_method","none") != "none")
            return@synchronized failure("invalid_client_metadata")
        val redirects = (0 until urls.length()).map { urls.optString(it) }
        if (redirects.any { !validRedirect(it) } || redirects.distinct().size != redirects.size)
            return@synchronized failure("invalid_redirect_uri")
        val label = obj.optString("client_name").take(80).ifBlank { "MCP agent" }
        val s = current(c); val clients = table(s,"clients")
        if (clients.length() >= 80) return@synchronized failure("registration_capacity_reached",429)
        val id = nonce()
        clients.put(id, JSONObject().put("name",label)
            .put("domain",URI(redirects.first()).host)
            .put("redirects",JSONArray(redirects)))
        save(c,s)
        reply(201,JSONObject().put("client_id",id).put("client_name",label)
            .put("redirect_uris",JSONArray(redirects))
            .put("token_endpoint_auth_method","none")
            .put("grant_types",JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types",JSONArray().put("code")))
    }
    fun authorize(c: Context, args: Map<String,String>): Reply = synchronized(lock) {
        val origin = issuer() ?: return@synchronized failure("temporarily_unavailable",503)
        val s = current(c)
        val client = table(s,"clients").optJSONObject(args["client_id"])
            ?: return@synchronized failure("invalid_client")
        val callback = args["redirect_uri"].orEmpty()
        if (!(0 until client.getJSONArray("redirects").length()).any {
                client.getJSONArray("redirects").optString(it) == callback
            }) return@synchronized failure("invalid_redirect_uri")
        if (args["response_type"] != "code" || args["code_challenge_method"] != "S256" ||
            !Regex("[A-Za-z0-9_-]{43,128}").matches(args["code_challenge"].orEmpty()) ||
            args["state"].orEmpty().length !in 8..2048 ||
            (args["resource"] != null && args["resource"] != origin + "/mcp") ||
            (args["scope"].orEmpty().isNotBlank() && args["scope"] != SCOPE))
            return@synchronized failure("invalid_request")
        prune(s,System.currentTimeMillis())
        val pending = table(s,"pending")
        if (pending.length() >= 32) return@synchronized failure("too_many_requests",429)
        val ticket = nonce()
        pending.put(digest(ticket),JSONObject().put("client",args["client_id"])
            .put("redirect",callback).put("challenge",args["code_challenge"])
            .put("state",args["state"]).put("aud",origin + "/mcp")
            .put("expiry",System.currentTimeMillis()+CODE_MS).put("attempts",0))
        save(c,s)
        val display = html(client.optString("name"))
        val domain = html(URI(callback).host.orEmpty())
        val page = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>MCP authorization</title><style>body{font-family:system-ui;background:#111827;color:#f9fafb;max-width:440px;margin:5vh auto;padding:24px}input,button{font-size:1rem;padding:12px;border-radius:8px;width:100%;box-sizing:border-box;margin-top:12px}button{background:#4f46e5;color:white;border:0}p{line-height:1.5}</style></head><body><h2>Authorize $display</h2><p>Requesting callback domain: <b>$domain</b></p><p>Grant this agent read-only access to Android diagnostic logs. Check the callback domain before approving. The agent does not receive your existing key.</p><form method="post" action="/oauth/approve"><input type="hidden" name="ticket" value="$ticket"><input type="password" name="key" autocomplete="off" required aria-label="MCP access key"><button type="submit">Approve access</button></form><p>Approval grants diagnostic reading, never shell commands.</p></body></html>"""
        Reply(200,page,"text/html")
    }
    fun approve(c: Context, form: Map<String,String>): Reply = synchronized(lock) {
        val s = current(c); val ticket = digest(form["ticket"].orEmpty())
        val pending = table(s,"pending")
        val record = pending.optJSONObject(ticket) ?: return@synchronized failure("invalid_request")
        if (record.optLong("expiry") <= System.currentTimeMillis())
            return@synchronized failure("expired_request")
        val master = BridgeClient.clientToken(c)
        val key = form["key"].orEmpty()
        if (master.isBlank() || key.length > 256 || !same(master,key)) {
            val attempts = record.optInt("attempts") + 1
            if (attempts >= 5) pending.remove(ticket) else record.put("attempts",attempts)
            save(c,s)
            return@synchronized Reply(403,"<html><body>Key rejected. Restart the authorization to try again.</body></html>","text/html")
        }
        pending.remove(ticket)
        val code = nonce()
        table(s,"codes").put(digest(code),JSONObject()
            .put("client",record.getString("client"))
            .put("redirect",record.getString("redirect"))
            .put("challenge",record.getString("challenge"))
            .put("aud",record.getString("aud"))
            .put("expiry",System.currentTimeMillis()+CODE_MS))
        save(c,s)
        val issuer = issuer() ?: return@synchronized failure("temporarily_unavailable",503)
        redirect(withParams(record.getString("redirect"),mapOf(
            "code" to code,"state" to record.getString("state"),"iss" to issuer)))
    }
    private fun issue(s: JSONObject, client: String, audience: String): Reply {
        val now = System.currentTimeMillis()
        val access = nonce()
        val refresh = nonce()
        val tokens = table(s,"tokens")
        if (tokens.length() >= 256) return failure("token_capacity_reached",429)
        tokens.put(digest(access), JSONObject().put("kind","access")
            .put("client",client).put("aud",audience).put("scope",SCOPE)
            .put("expiry",now+ACCESS_MS))
        tokens.put(digest(refresh), JSONObject().put("kind","refresh")
            .put("client",client).put("aud",audience).put("scope",SCOPE)
            .put("expiry",now+REFRESH_MS))
        return reply(200,JSONObject().put("access_token",access)
            .put("refresh_token",refresh).put("token_type","Bearer")
            .put("expires_in",ACCESS_MS/1000).put("scope",SCOPE))
    }
    fun token(c: Context, form: Map<String,String>): Reply = synchronized(lock) {
        val s = current(c)
        val id = form["client_id"].orEmpty()
        if (table(s,"clients").optJSONObject(id) == null)
            return@synchronized failure("invalid_client",401)
        val audience = resource() ?: return@synchronized failure("temporarily_unavailable",503)
        if (form["resource"] != null && form["resource"] != audience)
            return@synchronized failure("invalid_target")
        when (form["grant_type"]) {
            "authorization_code" -> {
                val key = digest(form["code"].orEmpty())
                val codes = table(s,"codes")
                val record = codes.optJSONObject(key)
                    ?: return@synchronized failure("invalid_grant")
                // Consume the one-time code, even on a bad verifier.
                codes.remove(key); save(c,s)
                if (record.optString("client") != id ||
                    record.optString("redirect") != form["redirect_uri"] ||
                    record.optString("aud") != audience ||
                    record.optLong("expiry") <= System.currentTimeMillis() ||
                    !pkceMatches(form["code_verifier"].orEmpty(),record.optString("challenge")))
                    return@synchronized failure("invalid_grant")
                val response = issue(s,id,audience)
                save(c,s); updateConnected(s)
                response
            }
            "refresh_token" -> {
                val key = digest(form["refresh_token"].orEmpty())
                val tokens = table(s,"tokens")
                val record = tokens.optJSONObject(key)
                    ?: return@synchronized failure("invalid_grant")
                tokens.remove(key)
                save(c,s)
                if (record.optString("kind") != "refresh" ||
                    record.optString("client") != id ||
                    record.optString("aud") != audience ||
                    record.optLong("expiry") <= System.currentTimeMillis())
                    return@synchronized failure("invalid_grant")
                val response = issue(s,id,audience)
                save(c,s); updateConnected(s)
                response
            }
            else -> failure("unsupported_grant_type")
        }
    }
    fun validate(c: Context, token: String): Boolean = synchronized(lock) {
        if (token.length !in 20..256) return@synchronized false
        val s = current(c)
        val record = table(s,"tokens").optJSONObject(digest(token))
            ?: return@synchronized false
        record.optString("kind") == "access" &&
            record.optString("scope") == SCOPE &&
            record.optString("aud") == resource() &&
            record.optLong("expiry") > System.currentTimeMillis() &&
            table(s,"clients").has(record.optString("client"))
    }
    fun revokeToken(c: Context, form: Map<String,String>): Reply = synchronized(lock) {
        val s = current(c)
        val client = form["client_id"].orEmpty()
        val token = form["token"].orEmpty()
        val item = table(s,"tokens").optJSONObject(digest(token))
        if (item != null && item.optString("client") == client)
            table(s,"tokens").remove(digest(token))
        save(c,s); updateConnected(s)
        reply(200,JSONObject())
    }
    fun revokeClient(c: Context, id: String) = synchronized(lock) {
        val s = current(c)
        val tokens = table(s,"tokens")
        for (key in tokens.keys().asSequence().toList()) {
            if (tokens.optJSONObject(key)?.optString("client") == id) tokens.remove(key)
        }
        // Clients can reauthorize later only by entering the existing master key.
        save(c,s); updateConnected(s)
    }
}
