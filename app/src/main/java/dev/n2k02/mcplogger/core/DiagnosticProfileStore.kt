package dev.n2k02.mcplogger.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-owner-managed presets. Stored privately and read by MCP; MCP clients
 * cannot create, overwrite, or delete profiles.
 */
internal object DiagnosticProfileStore {
    private const val PREFS = "diagnostic_logcat_profiles"
    private const val KEY = "profiles"
    private val safeName = Regex("[a-z][a-z0-9_-]{0,31}")
    private val custom = sortedMapOf<String, List<String>>()

    @Synchronized fun load(context: Context) {
        custom.clear()
        val serialized = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "{}").orEmpty()
        val parsed = runCatching { JSONObject(serialized) }.getOrElse { JSONObject() }
        val keys = parsed.keys()
        while (keys.hasNext() && custom.size < 20) {
            val name = keys.next()
            val tags = runCatching {
                val input = parsed.getJSONArray(name)
                (0 until input.length()).map { input.getString(it) }
            }.getOrNull() ?: continue
            if (valid(name, tags)) custom[name] = tags
        }
    }

    @Synchronized fun all(): Map<String, List<String>> = custom.toMap()

    @Synchronized fun get(name: String): List<String>? = custom[name]?.toList()

    @Synchronized fun save(context: Context, name: String, tags: List<String>) {
        require(valid(name, tags))
        require(name in custom || custom.size < 20)
        custom[name] = tags.toList()
        persist(context)
    }

    @Synchronized fun remove(context: Context, name: String) {
        require(name in custom)
        custom.remove(name)
        persist(context)
    }

    private fun valid(name: String, tags: List<String>): Boolean {
        if (!safeName.matches(name) || name in DiagnosticLogcat.profileNames() ||
            tags.isEmpty() || tags.size > 32) return false
        return runCatching {
            DiagnosticLogcat.parse(JSONObject().put("tags", JSONArray(tags)))
            true
        }.getOrDefault(false)
    }

    private fun persist(context: Context) {
        val obj = JSONObject()
        custom.forEach { (name, tags) -> obj.put(name, JSONArray(tags)) }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, obj.toString()).commit()) {
            "Could not persist diagnostic profile"
        }
    }
}
