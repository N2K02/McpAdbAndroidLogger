package dev.n2k02.mcplogger

import org.json.JSONArray
import org.json.JSONObject

/** Human-readable display of already-redacted audit payloads; never modifies saved JSON. */
internal object AuditJsonFormatter {
    fun pretty(input: String): String {
        val raw = input.trim()
        if (raw.isEmpty() || raw.length > 50_000) return input
        return runCatching {
            val parsed: Any = when {
                raw.startsWith("{") -> JSONObject(raw)
                raw.startsWith("[") -> JSONArray(raw)
                else -> return input
            }
            val normalized = normalize(parsed, 0)
            when (normalized) {
                is JSONObject -> normalized.toString(2)
                is JSONArray -> normalized.toString(2)
                else -> input
            }
        }.getOrDefault(input)
    }

    private fun normalize(value: Any, depth: Int): Any {
        if (depth > 6) return value
        return when (value) {
            is JSONObject -> JSONObject().also { out ->
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    out.put(key, normalize(value.get(key), depth + 1))
                }
            }
            is JSONArray -> JSONArray().also { out ->
                for (i in 0 until value.length()) out.put(normalize(value.get(i), depth + 1))
            }
            is String -> {
                val nested = value.trim()
                if (nested.length > 25_000 || nested.length < 2) value
                else runCatching {
                    when {
                        nested.startsWith("{") -> normalize(JSONObject(nested), depth + 1)
                        nested.startsWith("[") -> normalize(JSONArray(nested), depth + 1)
                        else -> value
                    }
                }.getOrDefault(value)
            }
            else -> value
        }
    }
}
