package dev.n2k02.mcplogger.core

/**
 * Fixed-command shell protocol for Perfetto diagnostics.
 *
 * ADB's persistent shell merges stdout/stderr and has no exit-code API.
 * Piping commands to 'head' hid the real exit code, reporting SQL errors as
 * an unavailable processor. This wrapper captures the original command exit
 * code and truncates only what is sent back to the app. No captured text,
 * trace packet, command, path or stderr is ever sent through MCP.
 */
internal object PerfettoStageDiagnostics {
    private const val MARKER = "__N2K02_PERFETTO_EXIT__="
    private const val MAX_RESULT_BYTES = 32768

    internal data class ShellResult(val output: String, val exitCode: Int?) {
        val succeeded: Boolean get() = exitCode == 0
    }

    /** Command is a fixed internal template; never accept client-supplied shell. */
    internal fun checked(command: String, maxOutputBytes: Int = 4096): String {
        require(command.isNotBlank() && '\n' !in command && '\r' !in command)
        require(maxOutputBytes in 64..MAX_RESULT_BYTES)
        // The ADB watchdog logs the first shell token on timeout. Keep it static.
        return ":; n2k02_out=\$($command 2>&1); n2k02_rc=\$?; " +
            "printf '%s\\n' \"\$n2k02_out\" | head -c $maxOutputBytes; " +
            "printf '\\n${MARKER}%s\\n' \"\$n2k02_rc\""
    }

    internal fun parse(raw: String): ShellResult {
        val markerPos = raw.lastIndexOf(MARKER)
        if (markerPos < 0) return ShellResult(raw.take(MAX_RESULT_BYTES), null)
        val suffix = raw.substring(markerPos + MARKER.length).trim()
        val code = suffix.toIntOrNull()?.takeIf { it in 0..255 }
        return ShellResult(raw.substring(0, markerPos).take(MAX_RESULT_BYTES).trim(), code)
    }

    /** Fixed, non-sensitive enums only. No raw CLI stderr in result/logs. */
    internal fun executionStatus(result: ShellResult): String {
        if (result.exitCode == null) return "no_exit_code"
        if (result.succeeded) return "ok"
        val lower = result.output.lowercase()
        return when {
            "permission denied" in lower || "not permitted" in lower ||
                "avc: denied" in lower -> "permission_denied"
            "cannot link executable" in lower || "exec format error" in lower ||
                "not executable" in lower -> "binary_incompatible"
            "no such file" in lower || "not found" in lower -> "binary_missing"
            "database or disk is full" in lower || "out of memory" in lower ->
                "resource_unavailable"
            else -> "execution_failed"
        }
    }

    internal fun queryStatus(result: ShellResult): String {
        if (result.exitCode == null) return "no_exit_code"
        val lower = result.output.lowercase()
        // Distinguish missing Android data-source tables from invalid queries
        // and unsupported trace-processor flags. Never return raw SQL or stderr.
        if ("no such table" in lower) return "source_table_missing"
        if ("no such column" in lower) return "source_column_missing"
        if ("syntax error" in lower || "parse error" in lower)
            return "sql_syntax_error"
        if ("unrecognized option" in lower || "unknown option" in lower ||
            "unrecognized arguments" in lower || "unknown command" in lower ||
            "invalid command" in lower) return "cli_unsupported"
        if ("sql error" in lower) return "sql_execution_error"
        if (!result.succeeded) return executionStatus(result)
        return "ok"
    }

    /** A supported SQL engine that has no FrameTimeline is not a broken binary. */
    internal fun resultStatus(code: String, hadData: Boolean, emptyStatus: String): String =
        if (code == "ok" && !hadData) emptyStatus else code

    internal fun traceStatus(result: ShellResult): String {
        if (result.exitCode == null) return "no_exit_code"
        if (result.succeeded) return "ok"
        val lower = result.output.lowercase()
        return when {
            "permission denied" in lower || "not permitted" in lower ->
                "permission_denied"
            "unknown data source" in lower || "failed to enable" in lower ||
                "unknown field" in lower || "could not resolve" in lower ->
                "data_source_unavailable"
            else -> "capture_failed"
        }
    }

    /** CSV rows are validated by the caller against the device's package catalog. */
    internal fun parseCpuMillis(output: String): Double? {
        val lines = output.lineSequence().map { it.trim().trim('"') }.toList()
        return lines.drop(1).firstNotNullOfOrNull { candidate ->
            candidate.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100000.0 }
        }
    }
}
