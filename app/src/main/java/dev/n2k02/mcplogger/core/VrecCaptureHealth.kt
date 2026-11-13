package dev.n2k02.mcplogger.core

import org.json.JSONObject

/** Strict whitelist for the helper's atomic, metadata-only live snapshot. */
internal object VrecCaptureHealth {
    fun parse(raw: String, nowMs: Long): JSONObject {
        fun unavailable(reason: String) = JSONObject().put("status", reason)
            .put("recording_state", "unknown")
        if (raw.isBlank()) return unavailable("unavailable_or_helper_not_updated")
        if (raw.toByteArray(Charsets.UTF_8).size > 1024) return unavailable("invalid")
        val fields = raw.trim().split(" | ")
        if (fields.size != 6 || fields[0] != "VREC_LIVE_1") return unavailable("invalid")
        val at = fields[1].removePrefix("at=").toLongOrNull() ?: return unavailable("invalid")
        val active = when (fields[2]) { "active=1" -> true; "active=0" -> false; else -> return unavailable("invalid") }
        val age = nowMs - at
        if (age < 0 || age > 20_000) return unavailable("stale").put("sampled_at", at)
        val values = fields[3].split(';').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()
        val state = values["mic"]?.takeIf { it in setOf("ON", "OFF", "SILENCED", "NO_DATA", "ZERO_SAMPLES") }
            ?: return unavailable("invalid")
        val source = values["source"]?.takeIf { it in setOf("NONE", "MIC", "VOICE_COMMUNICATION", "VOICE_RECOGNITION") }
            ?: return unavailable("invalid")
        val verdict = fields[5].takeIf { it in setOf("NO_AUDIO", "BOTH_CHANNELS_QUIET", "LEFT_CHANNEL_QUIET", "RIGHT_CHANNEL_QUIET", "CLIPPING", "AUDIO_DETECTED") }
            ?: return unavailable("invalid")
        val result = JSONObject().put("status", "fresh_snapshot")
            .put("sampled_at", at).put("age_ms", age)
            .put("recording_state", if (active) "active_at_sample" else "stopped_at_sample")
            .put("mic_state", state).put("mic_source", source).put("pcm_verdict", verdict)
            .put("source", "VRec shell helper live health v1")
            .put("notes", "A fresh helper sample, not proof both participants are audible. No audio or caller data.")
        values["attempts"]?.toIntOrNull()?.takeIf { it in 0..3 }?.let { result.put("mic_attempts", it) }
        values["reason"]?.takeIf { it in setOf("NONE", "OPEN_FAILED", "CALL_MIC_PROTECTED", "READ_FAILED", "READER_STOP_TIMEOUT", "POLICY_SILENCED", "ZERO_SAMPLES", "NO_DATA") }
            ?.let { result.put("mic_reason", it) }
        // Export only known numeric PCM metrics, never the unvalidated raw line.
        val rms = Regex("""([LR]) rms=([0-9]+(?:\.[0-9]+)?) silence=([0-9]+(?:\.[0-9]+)?)%""")
        for (match in rms.findAll(fields[4])) {
            val value = match.groupValues[2].toDoubleOrNull() ?: continue
            val silence = match.groupValues[3].toDoubleOrNull() ?: continue
            if (value.isFinite() && value in 0.0..32768.0 && silence in 0.0..100.0) {
                val side = if (match.groupValues[1] == "L") "playback" else "microphone"
                result.put(side + "_rms", value).put(side + "_silence_percent", silence)
            }
        }
        return result
    }
}
