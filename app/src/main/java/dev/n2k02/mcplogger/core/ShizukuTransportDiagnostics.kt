package dev.n2k02.mcplogger.core

import android.os.SystemClock
import dev.n2k02.mcplogger.adb.AdbTransport
import dev.n2k02.mcplogger.adb.ShizukuHostAdapter
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Shizuku-only transport health. No TCP connection, pairing, or integrated ADB
 * socket metrics are involved. A fixed "id -u" probe exercises the actual
 * verified Shizuku user-service Binder path, including its UID check.
 */
internal object ShizukuTransportDiagnostics {
    suspend fun profile(): JSONObject {
        val state = ShizukuHostAdapter.current()?.state?.value
        val connected = state?.connected == true && AdbTransport.isConnected
        val response = JSONObject()
            .put("transport", "shizuku_binder")
            .put("connected", connected)
            .put("verified_uid", state?.verifiedUid ?: JSONObject.NULL)
            .put("last_verified_at", state?.lastVerifiedAt ?: JSONObject.NULL)
            .put("source", "Shizuku user-service Binder and fixed shell UID probe")
            .put("taken_at", System.currentTimeMillis())
        if (!connected) return response.put("status", "unavailable")
            .put("reason", "shizuku_not_verified")
            .put("latency_ms", JSONObject.NULL)
        val start = SystemClock.elapsedRealtimeNanos()
        val result = try {
            AdbTransport.exec("id -u", timeoutMs = 5_000L)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return response.put("status", "unavailable")
                .put("reason", "binder_probe_failed")
                .put("latency_ms", JSONObject.NULL)
        }
        val latencyMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        return when {
            result.isFailure -> response.put("status", "unavailable")
                .put("reason", "binder_probe_failed")
                .put("latency_ms", JSONObject.NULL)
            result.getOrNull()?.trim() != "2000" ->
                response.put("status", "verification_failed")
                    .put("latency_ms", JSONObject.NULL)
            else -> response.put("status", "ok")
                .put("latency_ms", kotlin.math.round(latencyMs * 10.0) / 10.0)
        }
    }
}
