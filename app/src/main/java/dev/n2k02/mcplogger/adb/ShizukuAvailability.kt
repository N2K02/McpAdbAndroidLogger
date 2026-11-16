package dev.n2k02.mcplogger.adb

import android.content.pm.PackageManager
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Optional Shizuku/Sui backend readiness: never request permission silently.
 * A shell-UID Shizuku server has no extra privilege over ADB's shell UID.
 */
internal object ShizukuAvailability {
    const val PERMISSION_REQUEST = 4107

    data class State(val connected: Boolean, val authorized: Boolean, val serverUid: Int?) {
        val usable: Boolean get() = connected && authorized &&
            (serverUid == 2000 || serverUid == 0)
    }

    fun state(): State = runCatching {
        if (!Shizuku.pingBinder()) return@runCatching State(false, false, null)
        val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        val uid = if (granted) Shizuku.getUid() else null
        State(true, granted, uid)
    }.getOrDefault(State(false, false, null))

    /** Explicit only: invoked by the user tapping Authorize in Shizuku Settings. */
    fun requestPermission(): Boolean {
        val current = state()
        if (!current.connected || current.authorized) return false
        return runCatching {
            Shizuku.requestPermission(PERMISSION_REQUEST)
            true
        }.getOrDefault(false)
    }

    fun snapshot(): JSONObject {
        val s = state()
        return JSONObject().put("connected", s.connected)
            .put("permission_granted", s.authorized)
            .put("server_uid", s.serverUid ?: JSONObject.NULL)
            .put("eligible_shell_or_root", s.usable)
            .put("execution_backend", if (dev.n2k02.mcplogger.shizuku.ShizukuReadBackend.isReady())
                "shizuku_fixed_reads_with_adb_fallback" else "adb")
            .put("reason", "Only fixed thermal, audio, service discovery and JobScheduler reads " +
                "use an authorized UserService; other operations stay on ADB")
            .put("hidden_api_bypass", false)
            .put("binder_received", s.connected)
            .put("user_service_bound",
                dev.n2k02.mcplogger.shizuku.ShizukuReadBackend.isReady())
            .put("user_service_lifecycle",
                dev.n2k02.mcplogger.shizuku.ShizukuReadBackend.diagnosticStatus())
            .put("connection_diagnostic", when {
                !s.connected -> "shizuku_binder_unavailable"
                !s.authorized -> "permission_not_granted"
                !s.usable -> "unsupported_server_uid"
                dev.n2k02.mcplogger.shizuku.ShizukuReadBackend.isReady() -> "ready"
                else -> dev.n2k02.mcplogger.shizuku.ShizukuReadBackend.diagnosticStatus()
            })
    }
}
