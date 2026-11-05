package dev.n2k02.mcplogger.core

import org.json.JSONArray
import org.json.JSONObject

/** Stateless, read-only MCP Streamable HTTP over JSON-RPC. No remote shell access. */
internal object LocalMcpProtocol {
    const val VERSION = "2025-11-25"
    private val names = listOf("get_device_status", "list_installed_apps", "get_live_logs",
        "search_logs", "get_crashes", "get_anrs", "get_cpu_usage",
        "get_frame_timing", "get_performance_snapshot",
        "get_battery_diagnostics", "get_memory_pressure", "get_wakelock_usage",
        "get_screen_off_baseline", "get_screen_off_report")

    fun invalidJson() = error(JSONObject.NULL, -32700, "Parse error")
    private fun error(id: Any, code: Int, text: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", text))

    /** Optional local-only capability; null means the feature is disabled. */
    fun execute(req: JSONObject, auditReader: (() -> List<McpAuditEntry>)? = null,
                logsReader: (() -> List<LogEvent>)? = null,
                appsReader: (() -> List<String>)? = null,
                performanceReader: ((String, JSONObject) -> JSONObject)? = null): JSONObject? {
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
                    val name = params.optString("name")
                    MonitorState.beginMcpTool(name)
                    val body = try {
                        readTool(name, params.optJSONObject("arguments") ?: JSONObject(),
                            auditReader, logsReader, appsReader, performanceReader)
                    } finally {
                        MonitorState.endMcpTool(name)
                    }
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
            "Read cached ADB shell status and whether a Logcat request is currently running",
            "Fetch the installed third-party app list from Android on demand",
            "Fetch one bounded Android Logcat snapshot on demand; return redacted events using a cursor and optional verified package_name",
            "Search a freshly fetched bounded Android Logcat snapshot; optionally filter by verified package_name",
            "Fetch recent Android crashes on demand; optionally filter by verified package_name",
            "Fetch recent Android ANRs on demand; optionally filter by verified package_name",
            "Read one bounded top CPU process snapshot; optional exact package_name filter",
            "Read recent UI frame timing and GPU completion latency (not GPU utilization) for one app",
            "Read CPU plus optional app UI frame timing in a bounded on-demand request",
            "Read battery temperature, charge state and estimated per-UID energy since charge on demand",
            "Read RAM, swap and kernel PSI memory stall metrics in a bounded on-demand snapshot",
            "Read currently held partial wakelocks plus per-UID partial hold times since charge",
            "Store an in-memory battery/wakelock baseline for a later screen-off comparison; no polling",
            "Read delta wake-lock durations and actual reported screen-off duration since the baseline",
            "Read sanitized, paginated MCP audit history (requires local owner opt-in)"
        )
        return JSONArray().apply {
            toolNames.forEachIndexed { index, name ->
                val fields = JSONObject()
                if (name in setOf("list_installed_apps", "get_live_logs", "search_logs",
                        "get_crashes", "get_anrs", "get_cpu_usage", "get_wakelock_usage",
                        "get_screen_off_report", "get_mcp_audits")) {
                    fields.put("limit", JSONObject().put("type", "integer")
                        .put("description", if (name == "get_mcp_audits") "1-25"
                            else if (name in setOf("get_cpu_usage", "get_wakelock_usage",
                                    "get_screen_off_report")) "1-50" else "1-500"))
                }
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
                if (name in setOf("get_cpu_usage", "get_frame_timing", "get_performance_snapshot")) {
                    fields.put("package_name", JSONObject().put("type", "string")
                        .put("description", "Exact Android package, e.g. com.vrec.app. Required for frame timing."))
                    if (name != "get_cpu_usage") {
                        fields.put("frame_budget_ms", JSONObject().put("type", "number")
                            .put("description", "Frame budget 5-50 ms; 16.67 is 60 Hz and 8.33 is 120 Hz."))
                    }
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
                if (name == "get_frame_timing") schema.put("required", JSONArray().put("package_name"))
                put(JSONObject().put("name", name).put("description", descriptions[index])
                    .put("inputSchema", schema)
                    .put("securitySchemes", JSONArray().put(
                        JSONObject().put("type", "oauth2")
                            .put("scopes", JSONArray().put("logcat:read")))))
            }
        }
    }

    private fun readTool(name: String, args: JSONObject,
                         auditReader: (() -> List<McpAuditEntry>)?,
                         logsReader: (() -> List<LogEvent>)?,
                         appsReader: (() -> List<String>)?,
                         performanceReader: ((String, JSONObject) -> JSONObject)?): Any {
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        return when (name) {
            "get_device_status" -> JSONObject().put("adb", MonitorState.adb.value)
                .put("monitoring", MonitorState.active.value)
                .put("local_mcp", MonitorState.localMcp.value)
                .put("package", MonitorState.selectedPackage.value)
                .put("buffered_logs", MonitorState.logs.value.size)
            "list_installed_apps" -> JSONArray((appsReader?.invoke() ?: MonitorState.packages.value).take(limit))
            "get_mcp_audits" -> {
                val reader = auditReader ?: throw IllegalArgumentException(
                    "MCP audit history access is disabled")
                McpAuditPolicy.agentPage(reader(), args.optInt("limit", 20),
                    args.optString("before_id"), args.optBoolean("include_payloads", false))
            }
            "get_cpu_usage", "get_frame_timing", "get_performance_snapshot",
            "get_battery_diagnostics", "get_memory_pressure", "get_wakelock_usage",
            "get_screen_off_baseline", "get_screen_off_report" -> {
                val performance = name in setOf("get_cpu_usage", "get_frame_timing",
                    "get_performance_snapshot")
                val pkg = if (performance) args.optString("package_name", "") else ""
                if (performance) {
                    require((pkg.isBlank() && name != "get_frame_timing") ||
                        PerformanceDiagnostics.validPackage(pkg))
                }
                val budget = args.optDouble("frame_budget_ms", 16.67)
                if (performance && name != "get_cpu_usage")
                    require(budget.isFinite() && budget in 5.0..50.0)
                val limitCpu = args.optInt("limit", 20).coerceIn(1, 50)
                val safeArgs = JSONObject().put("package_name", pkg)
                    .put("limit", limitCpu).put("frame_budget_ms", budget)
                performanceReader?.invoke(name, safeArgs)
                    ?: throw IllegalStateException("Performance diagnostics unavailable")
            }
            "get_live_logs", "search_logs", "get_crashes", "get_anrs" -> {
                val after = args.optLong("after_cursor", 0).coerceAtLeast(0)
                val query = args.optString("query").take(200)
                val level = args.optString("level").take(1)
                if (name == "search_logs" && query.isBlank()) throw IllegalArgumentException()
                val events = logsReader?.invoke() ?: LogMonitorService.snapshot(0, 2000)
                val logs = McpLogQuery.select(
                    // Scan the fresh bounded Android buffer before applying UID filters.
                    // A cursor is assigned only for previously unseen entries.
                    events = events.filter { it.id > after },
                    tool = name,
                    packageName = args.optString("package_name", ""),
                    packageUids = MonitorState.packageUids.value,
                    query = query,
                    level = level,
                    limit = limit,
                )
                // Mirror the most recent successful MCP request in the Logs screen.
                // Store IDs rather than copying log messages, and preserve the tool's
                // returned limit / cursor selection until the user edits UI filters.
                val nextFilter = LogViewFilter(
                    packageName = args.optString("package_name", "").trim(),
                    search = query,
                    selectedLevels = if (name == "get_live_logs" &&
                        level.uppercase() in setOf("V", "D", "I", "W", "E", "F"))
                        setOf(level.uppercase()) else emptySet(),
                    crashesOnly = name == "get_crashes" || name == "get_anrs",
                )
                MonitorState.logFilter.value = nextFilter
                MonitorState.selectedPackage.value = nextFilter.packageName
                MonitorState.recentLogRequest.value = RecentLogRequest(
                    origin = LogReadOrigin.MCP,
                    tool = name,
                    atMillis = System.currentTimeMillis(),
                    filter = nextFilter,
                    entryIds = logs.mapTo(mutableSetOf()) { it.id }
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
