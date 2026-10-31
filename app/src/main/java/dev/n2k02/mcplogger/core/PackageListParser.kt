package dev.n2k02.mcplogger.core

/**
 * Parses "pm list packages -3 -U" output produced by the authorized shell.
 * Using shell-reported UIDs avoids requesting Android QUERY_ALL_PACKAGES access.
 */
object PackageListParser {
    private val row = Regex("""^package:([A-Za-z0-9_][A-Za-z0-9_.]*)\s+uid:(\d+)\s*$""")
    fun parse(output: String): Map<String, String> =
        output.lineSequence().mapNotNull { raw ->
            val match = row.matchEntire(raw.trim()) ?: return@mapNotNull null
            match.groupValues[1] to match.groupValues[2]
        }.toMap()
}
