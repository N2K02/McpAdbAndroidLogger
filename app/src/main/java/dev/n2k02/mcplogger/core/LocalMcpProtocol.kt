package dev.n2k02.mcplogger.core

import org.json.JSONArray
import org.json.JSONObject

/** Stateless, read-only MCP Streamable HTTP over JSON-RPC. No remote shell access. */
internal object LocalMcpProtocol {
    const val VERSION = "2025-11-25"
    // Readable through the original get_device_status tool even when an MCP client
    // still has an old tools/list cache. Never starts a shell diagnostic.
    const val CATALOG_REVISION = "2026-11-09-diagnostic-logcat-2"
    private val names = listOf("get_device_status", "list_installed_apps", "get_live_logs",
        "search_logs", "get_crashes", "get_anrs", "get_cpu_usage",
        "get_frame_timing", "get_performance_snapshot",
        "get_battery_diagnostics", "get_memory_pressure", "get_wakelock_usage",
        "get_screen_off_baseline", "get_screen_off_report",
        "get_surfaceflinger_layers", "get_surfaceflinger_activity",
        "get_surfaceflinger_perfetto", "list_logcat_profiles",
        "get_diagnostic_correlation")

    internal fun builtInToolNames(): List<String> = names.toList()

    fun invalidJson() = error(JSONObject.NULL, -32700, "Parse error")
    private fun error(id: Any, code: Int, text: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", text))

    /** Optional local-only capability; null means the feature is disabled. */
    fun execute(req: JSONObject, auditReader: (() -> List<McpAuditEntry>)? = null,
                logsReader: (() -> List<LogEvent>)? = null,
                appsReader: (() -> List<String>)? = null,
                performanceReader: ((String, JSONObject) -> JSONObject)? = null,
                captureReader: ((DiagnosticLogcat.Options) -> List<LogEvent>)? = null): JSONObject? {
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
                        .put("version", CATALOG_REVISION))
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", toolList())
                "tools/call" -> {
                    val params = req.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name")
                    MonitorState.beginMcpTool(name)
                    val body = try {
                        readTool(name, params.optJSONObject("arguments") ?: JSONObject(),
                            auditReader, logsReader, appsReader, performanceReader, captureReader)
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

    private fun toolList(): JSONArray {
        val toolNames = names
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
            "List app-associated SurfaceFlinger layers (not per-app CPU or GPU); private layer titles omitted",
            "Sample 3-10 seconds of SurfaceFlinger TimeStats frames and compositor CPU, rank likely app render contributors; momentarily resets TimeStats",
            "Collect a 3-10 second Perfetto FrameTimeline and scheduling trace where an on-device processor is available; returns sanitized per-app counts and shared compositor CPU only",
            "List built-in and locally saved diagnostic Logcat profiles",
            "Capture bounded contemporaneous Logcat, CPU, memory and battery diagnostics"
        )
        return JSONArray().apply {
            toolNames.forEachIndexed { index, name ->
                val fields = JSONObject()
                if (name in setOf("list_installed_apps", "get_live_logs", "search_logs",
                        "get_crashes", "get_anrs", "get_cpu_usage", "get_wakelock_usage",
                        "get_screen_off_report", "get_surfaceflinger_layers",
                        "get_surfaceflinger_activity", "get_surfaceflinger_perfetto")) {
                    fields.put("limit", JSONObject().put("type", "integer")
                        .put("description", if (name in setOf("get_cpu_usage", "get_wakelock_usage",
                                    "get_screen_off_report", "get_surfaceflinger_layers",
                                    "get_surfaceflinger_activity", "get_surfaceflinger_perfetto")) "1-50" else "1-500"))
                }
                if (name in setOf("get_surfaceflinger_activity", "get_surfaceflinger_perfetto")) {
                    fields.put("duration_seconds", JSONObject().put("type", "integer")
                        .put("description", "Explicit capture duration 3-10 seconds, default 5."))
                }
                if (name in setOf("get_live_logs", "search_logs", "get_crashes", "get_anrs")) {
                    fields.put("profile", JSONObject().put("type", "string"))
                    fields.put("tags", JSONObject().put("type", "array")
                        .put("items", JSONObject().put("type", "string")))
                    fields.put("buffers", JSONObject().put("type", "array")
                        .put("items", JSONObject().put("type", "string")))
                    fields.put("format", JSONObject().put("type", "string"))
                    fields.put("mode", JSONObject().put("type", "string")
                        .put("description", "snapshot only; no remote continuous capture"))
                    fields.put("since", JSONObject().put("type", "integer")
                        .put("description", "Epoch milliseconds"))
                    fields.put("pid", JSONObject().put("type", "integer"))
                }
                if (name == "get_live_logs") {
                    fields.put("after_cursor", JSONObject().put("type", "integer"))
                    fields.put("level", JSONObject().put("type", "string"))
                    fields.put("query", JSONObject().put("type", "string"))
                }
                if (name == "get_diagnostic_correlation") {
                    fields.put("package_name", JSONObject().put("type", "string"))
                    fields.put("profile", JSONObject().put("type", "string"))
                    fields.put("limit", JSONObject().put("type", "integer"))
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
                         performanceReader: ((String, JSONObject) -> JSONObject)?,
                         captureReader: ((DiagnosticLogcat.Options) -> List<LogEvent>)?): Any {
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        return when (name) {
            "get_device_status" -> JSONObject().put("adb", MonitorState.adb.value)
                .put("monitoring", MonitorState.active.value)
                .put("local_mcp", MonitorState.localMcp.value)
                .put("package", MonitorState.selectedPackage.value)
                .put("buffered_logs", MonitorState.logs.value.size)
                .put("mcp_catalog_revision", CATALOG_REVISION)
                .put("mcp_tool_count", names.size)
                .put("mcp_tool_names", JSONArray(names))
                .put("on_demand_health_available", names.containsAll(listOf(
                    "get_cpu_usage", "get_battery_diagnostics", "get_memory_pressure",
                    "get_wakelock_usage", "get_screen_off_baseline", "get_screen_off_report")))
                .put("on_demand_health_ready", MonitorState.adb.value.startsWith(
                    "UID 2000 verified"))
            "list_logcat_profiles" -> DiagnosticLogcat.catalog()
            "list_installed_apps" -> JSONArray((appsReader?.invoke() ?: MonitorState.packages.value).take(limit))
            "get_cpu_usage", "get_frame_timing", "get_performance_snapshot",
            "get_battery_diagnostics", "get_memory_pressure", "get_wakelock_usage",
            "get_screen_off_baseline", "get_screen_off_report",
            "get_surfaceflinger_layers", "get_surfaceflinger_activity",
            "get_surfaceflinger_perfetto" -> {
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
                val duration = args.optInt("duration_seconds", 5)
                if (name in setOf("get_surfaceflinger_activity", "get_surfaceflinger_perfetto"))
                    require(duration in 3..10)
                val limitCpu = args.optInt("limit", 20).coerceIn(1, 50)
                val safeArgs = JSONObject().put("package_name", pkg)
                    .put("limit", limitCpu).put("frame_budget_ms", budget)
                    .put("duration_seconds", duration)
                performanceReader?.invoke(name, safeArgs)
                    ?: throw IllegalStateException("Performance diagnostics unavailable")
            }
            "get_diagnostic_correlation" -> {
                val pkg = args.optString("package_name", "")
                require(pkg.isEmpty() || PerformanceDiagnostics.validPackage(pkg))
                val options = DiagnosticLogcat.parse(JSONObject().apply {
                    if (args.has("profile")) put("profile", args.getString("profile"))
                })
                val before = System.currentTimeMillis()
                val events = captureReader?.invoke(options) ?: logsReader?.invoke()
                    ?: throw IllegalStateException("Logcat capture unavailable")
                val verified = McpLogQuery.select(
                    DiagnosticLogcat.select(events, options),
                    "get_live_logs", pkg, MonitorState.packageUids.value,
                    limit = args.optInt("limit", 50).coerceIn(1, 100))
                val reader = performanceReader ?:
                    throw IllegalStateException("Performance diagnostics unavailable")
                val cpu = reader.invoke("get_cpu_usage",
                    JSONObject().put("package_name", pkg).put("limit", 20))
                    ?: throw IllegalStateException("CPU diagnostics unavailable")
                val memory = reader.invoke("get_memory_pressure", JSONObject())
                val battery = reader.invoke("get_battery_diagnostics", JSONObject())
                val after = System.currentTimeMillis()
                JSONObject().put("window_start_ms", before)
                    .put("window_end_ms", after)
                    .put("source", "sequential bounded snapshots, not simultaneous sampling")
                    .put("causality", "Temporal overlap does not establish causality")
                    .put("package_name", pkg)
                    .put("logcat", JSONArray().apply {
                        verified.forEach { event ->
                            put(JSONObject().put("time", event.epochMillis)
                                .put("uid", event.uid.take(32))
                                .put("pid", event.pid.take(32))
                                .put("level", event.level.take(1))
                                .put("tag", SensitiveLogRedactor.redact(event.tag).take(120))
                                .put("message", SensitiveLogRedactor.redact(event.message).take(2000)))
                        }
                    })
                    .put("cpu", cpu).put("memory", memory).put("battery", battery)
            }
            "get_live_logs", "search_logs", "get_crashes", "get_anrs" -> {
                val after = args.optLong("after_cursor", 0).coerceAtLeast(0)
                val query = args.optString("query").take(200)
                val level = args.optString("level").take(1)
                if (name == "search_logs" && query.isBlank()) throw IllegalArgumentException()
                val options = DiagnosticLogcat.parse(args)
                val events = DiagnosticLogcat.select(
                    (captureReader?.invoke(options) ?: logsReader?.invoke()
                        ?: LogMonitorService.snapshot(0, 2000)), options)
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
                // Remote arguments scope only this MCP response. The on-device
                // Logs filters, selected app, and manual review remain unchanged.
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
