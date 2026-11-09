package dev.n2k02.mcplogger.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Snapshot-only triage; does not read Android's private dropbox, tombstone paths,
 * or unredacted crash dumps. All events are from the bounded MCP Logcat reader.
 * System-UID events are not attributed to arbitrary third-party applications.
 */
internal object CrashTriageDiagnostics {
    private val exception = Regex("""\b([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:Exception|Error))\b""")
    private val signal = Regex("""(?i)\b(?:fatal signal|signal)\s+(\d{1,2})\b""")
    private val crashTag = Regex("""(?i)(?:AndroidRuntime|DEBUG|libc|crash)""")
    private val anrTag = Regex("""(?i)(?:am_anr|ActivityManager)""")

    private fun failure(event: LogEvent): Boolean {
        val msg = event.message
        return msg.contains("FATAL EXCEPTION", true) ||
            msg.contains("Fatal signal", true) ||
            msg.contains("ANR in ", true) ||
            (event.level in listOf("E", "F") && (
                crashTag.containsMatchIn(event.tag) && exception.containsMatchIn(msg) ||
                    anrTag.containsMatchIn(event.tag) && msg.contains("not responding", true)))
    }

    private fun signature(event: LogEvent): Pair<String, String> {
        val msg = event.message
        return when {
            msg.contains("ANR in ", true) || msg.contains("not responding", true) ->
                "anr" to "application_not_responding"
            signal.containsMatchIn(msg) -> "native_crash" to
                ("signal_" + (signal.find(msg)?.groupValues?.get(1) ?: "unknown"))
            msg.contains("FATAL EXCEPTION", true) -> "java_crash" to
                (exception.find(msg)?.groupValues?.get(1)?.take(100) ?: "fatal_exception")
            exception.containsMatchIn(msg) -> "java_crash" to
                (exception.find(msg)?.groupValues?.get(1)?.take(100) ?: "exception")
            else -> "crash" to "unclassified_crash"
        }
    }

    internal fun summarize(events: List<LogEvent>, pkg: String,
        uids: Map<String, String>, limit: Int = 25): JSONObject {
        require(pkg.isBlank() || PerformanceDiagnostics.validPackage(pkg))
        val uid = if (pkg.isBlank()) null else uids[pkg]
        val allowed = if (pkg.isBlank()) events else if (uid == null) emptyList()
            else events.filter { it.uid == uid }
        val evidence = allowed.filter(::failure).takeLast(200)
        val groups = evidence.groupBy {
            val (kind, key) = signature(it)
            Triple(kind, key, it.uid.take(16))
        }.entries.sortedByDescending { it.value.size }
            .take(limit.coerceIn(1, 50))
        val rows = JSONArray()
        for ((key, matched) in groups) {
            val (kind, signature, eventUid) = key
            rows.put(JSONObject().put("type", kind)
                .put("signature", signature)
                .put("uid", eventUid)
                .put("count", matched.size)
                .put("first_seen_ms", matched.minOf { it.epochMillis })
                .put("last_seen_ms", matched.maxOf { it.epochMillis })
                .put("sample_tags", JSONArray(matched.map {
                    SensitiveLogRedactor.redact(it.tag).take(80)
                }.distinct().take(4))))
        }
        return JSONObject().put("status", when {
            pkg.isNotBlank() && uid == null -> "package_uid_unavailable"
            evidence.isEmpty() -> "insufficient_evidence"
            else -> "events_detected"
        }).put("package_name", pkg)
            .put("source", "bounded_on_demand_logcat")
            .put("event_count", evidence.size)
            .put("signatures", rows)
            .put("sampled_event_limit", 2000)
            .put("coverage", "Recent Logcat only, not persistent crash history. " +
                "App filters use verified UID and omit system-UID events even if they mention the package.")
            .put("notes", "Grouped signatures are heuristic. Absence of matches does not prove " +
                "the app has not crashed. Raw stack traces, device paths and event messages are omitted.")
    }
}
