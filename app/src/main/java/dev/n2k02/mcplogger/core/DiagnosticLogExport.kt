package dev.n2k02.mcplogger.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Owner-only exports; all fields are redacted before serialization. */
internal object DiagnosticLogExport {
    private const val MAX_RECORDS = 2000

    fun json(events: List<LogEvent>): String {
        val rows = JSONArray()
        events.takeLast(MAX_RECORDS).forEach { event ->
            rows.put(JSONObject()
                .put("time", event.epochMillis)
                .put("uid", event.uid.take(32))
                .put("pid", event.pid.take(32))
                .put("level", event.level.take(1))
                .put("tag", SensitiveLogRedactor.redact(event.tag).take(120))
                .put("message", SensitiveLogRedactor.redact(event.message).take(2000)))
        }
        return JSONObject().put("schema", "n2k02-diagnostic-logcat-v1")
            .put("exported_at", System.currentTimeMillis())
            .put("events", rows).toString(2)
    }

    fun text(events: List<LogEvent>): String =
        LogExportFormatter.export(events.takeLast(MAX_RECORDS))

    fun zip(events: List<LogEvent>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in listOf(
                "logcat.json" to json(events).toByteArray(Charsets.UTF_8),
                "logcat.txt" to text(events).toByteArray(Charsets.UTF_8)
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
