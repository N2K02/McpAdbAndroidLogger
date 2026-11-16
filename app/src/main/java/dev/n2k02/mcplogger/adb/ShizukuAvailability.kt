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

    /** Explicit only: invoked by the user tapping a button in ADB Settings. */
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
            .put("execution_backend", "adb")
            .put("reason", "Shizuku is an optional capability; system-service calls " +
                "require a separately reviewed privileged UserService and permission checks")
            .put("hidden_api_bypass", false)
    }
}
