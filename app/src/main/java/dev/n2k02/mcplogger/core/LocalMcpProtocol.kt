package dev.n2k02.mcplogger.core

import org.json.JSONArray
import org.json.JSONObject

/** Stateless, read-only MCP Streamable HTTP over JSON-RPC. No remote shell access. */
internal object LocalMcpProtocol {
    const val VERSION = "2025-11-25"
    private val names = listOf("get_device_status", "list_installed_apps", "get_live_logs",
        "search_logs", "get_crashes", "get_anrs")

    fun invalidJson() = error(JSONObject.NULL, -32700, "Parse error")
    private fun error(id: Any, code: Int, text: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", text))

    /** Optional local-only capability; null means the feature is disabled. */
    fun execute(req: JSONObject, auditReader: (() -> List<McpAuditEntry>)? = null): JSONObject? {
        val id = req.opt("id") ?: JSONObject.NULL
        val method = req.optString("method")
        if (req.optString("jsonrpc") != "2.0" || method.isEmpty())
            return error(id, -32600, "Invalid JSON-RPC request")
        if (!req.has("id")) return null
        return try {
            val result: Any = when (method) {
                "initialize" -> JSONObject().put("protocolVersion", VERSION)
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "MCP ADB Android Logger")
                        .put("version", "1.0"))
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", toolList(auditReader != null))
                "tools/call" -> {
                    val params = req.optJSONObject("params") ?: JSONObject()
                    val body = readTool(params.optString("name"),
                        params.optJSONObject("arguments") ?: JSONObject(), auditReader)
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text")
                        .put("text", body.toString()))).put("isError", false)
                }
                else -> return error(id, -32601, "Method not found")
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        } catch (_: IllegalArgumentException) {
            error(id, -32602, "Invalid tool arguments")
        } catch (_: Exception) {
            error(id, -32603, "Internal error")
        }
    }

    private fun toolList(includeAudits: Boolean): JSONArray {
        val toolNames = if (includeAudits) names + "get_mcp_audits" else names
        val descriptions = listOf(
            "Read cached ADB shell status and monitoring state",
            "List cached installed third-party apps",
            "Read redacted recent log events using a cursor; optionally filter by exact installed package_name (UID verified)",
            "Search recent redacted log events; optionally filter by exact installed package_name (UID verified)",
            "Read recent Android crashes; optionally filter by exact installed package_name (UID verified)",
            "Read recent Android ANR events; optionally filter by exact installed package_name (UID verified)",
            "Read sanitized, paginated MCP audit history (requires local owner opt-in)"
        )
        return JSONArray().apply {
            toolNames.forEachIndexed { index, name ->
                val fields = JSONObject().put("limit",
                    JSONObject().put("type", "integer").put("description",
                        if (name == "get_mcp_audits") "1-25" else "1-500"))
                if (name == "get_live_logs") {
                    fields.put("after_cursor", JSONObject().put("type", "integer"))
                    fields.put("level", JSONObject().put("type", "string"))
                    fields.put("query", JSONObject().put("type", "string"))
                }
                if (name == "search_logs") fields.put("query", JSONObject().put("type", "string"))
                if (name in setOf("get_live_logs", "search_logs", "get_crashes", "get_anrs")) {
                    fields.put("package_name", JSONObject().put("type", "string")
                        .put("description", "Optional exact Android package name from list_installed_apps, " +
                            "e.g. com.example.app. Matches the verified UID, not mentions in log text. " +
                            "Unknown or unavailable packages return no logs; omit for all packages."))
                }
                if (name == "get_mcp_audits") {
                    fields.put("before_id", JSONObject().put("type", "string")
                        .put("description", "Optional cursor from next_before_id"))
                    fields.put("include_payloads", JSONObject().put("type", "boolean")
                        .put("description", "Show re-redacted request/response previews; default false"))
                }
                val schema = JSONObject().put("type", "object").put("properties", fields)
                    .put("additionalProperties", false)
                if (name == "search_logs") schema.put("required", JSONArray().put("query"))
                put(JSONObject().put("name", name).put("description", descriptions[index])
                    .put("inputSchema", schema)
                    .put("securitySchemes", JSONArray().put(
                        JSONObject().put("type", "oauth2")
                            .put("scopes", JSONArray().put("logcat:read")))))
            }
        }
    }

    private fun readTool(name: String, args: JSONObject,
                         auditReader: (() -> List<McpAuditEntry>)?): Any {
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        return when (name) {
            "get_device_status" -> JSONObject().put("adb", MonitorState.adb.value)
                .put("monitoring", MonitorState.active.value)
                .put("local_mcp", MonitorState.localMcp.value)
                .put("package", MonitorState.selectedPackage.value)
                .put("buffered_logs", MonitorState.logs.value.size)
            "list_installed_apps" -> JSONArray(MonitorState.packages.value.take(limit))
            "get_mcp_audits" -> {
                val reader = auditReader ?: throw IllegalArgumentException(
                    "MCP audit history access is disabled")
                McpAuditPolicy.agentPage(reader(), args.optInt("limit", 20),
                    args.optString("before_id"), args.optBoolean("include_payloads", false))
            }
            "get_live_logs", "search_logs", "get_crashes", "get_anrs" -> {
                val after = args.optLong("after_cursor", 0).coerceAtLeast(0)
                val query = args.optString("query").take(200)
                val level = args.optString("level").take(1)
                if (name == "search_logs" && query.isBlank()) throw IllegalArgumentException()
                val logs = McpLogQuery.select(
                    // Search the full bounded 2,000-entry capture buffer before filtering
                    // so noisy unrelated apps cannot hide recent matching records.
                    events = LogMonitorService.snapshot(after, 2000),
                    tool = name,
                    packageName = args.optString("package_name", ""),
                    packageUids = MonitorState.packageUids.value,
                    query = query,
                    level = level,
                    limit = limit,
                )
                JSONArray().apply {
                    logs.forEach { e ->
                        put(JSONObject().put("cursor", e.id).put("time", e.epochMillis)
                            .put("uid", e.uid.take(32)).put("pid", e.pid.take(32))
                            .put("level", e.level.take(1))
                            .put("tag", SensitiveLogRedactor.redact(e.tag).take(120))
                            .put("message", SensitiveLogRedactor.redact(e.message).take(2000)))
                    }
                }
            }
            else -> throw IllegalArgumentException()
        }
    }
}
