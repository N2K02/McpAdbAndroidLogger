package dev.n2k02.mcplogger.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import dev.n2k02.mcplogger.adb.ShizukuAvailability
import dev.n2k02.mcplogger.adb.ShizukuPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Optional fixed-operation Binder backend. The ADB core remains authoritative
 * for pairing, authorization and normal shell. No implicit permission requests.
 * If Shizuku dies or refuses an operation, callers use the existing ADB read.
 */
internal object ShizukuReadBackend {
    const val THERMAL = 1
    const val CAPABILITIES = 2
    const val JOBS = 3
    const val HIDDEN_SERVICE_PROBE = 4

    @Volatile private var remote: IReadOnlyDiagnostics? = null
    @Volatile private var enabledForProcess: Boolean = true
    @Volatile private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    @Volatile private var bindingStartedAtMs: Long = 0L
    @Volatile private var bindingStatus: String = "not_requested"

    /** Small fixed statuses only: never export Binder/exception details. */
    fun diagnosticStatus(): String = when {
        isReady() -> "ready"
        !enabledForProcess -> "disabled"
        bindingStatus == "binding" &&
            SystemClock.elapsedRealtime() - bindingStartedAtMs > 15_000L -> "binding_timeout"
        else -> bindingStatus
    }

    fun isReady(): Boolean = enabledForProcess && ShizukuAvailability.state().usable &&
        remote?.asBinder()?.pingBinder() == true

    @Synchronized fun bindIfAuthorized(context: Context): Boolean {
        enabledForProcess = ShizukuPreferences.enabled(context)
        if (!enabledForProcess || !ShizukuAvailability.state().usable) return false
        if (isReady()) return true
        if (connection != null) {
            // Shizuku may drop the delivery callback; the old request must not
            // block explicit Reconnect forever. Rebind after a bounded deadline.
            if (diagnosticStatus() != "binding_timeout") return false
            shutdown()
        }
        val request = Shizuku.UserServiceArgs(
            ComponentName(context.applicationContext, ReadOnlyShizukuService::class.java))
            .daemon(false).processNameSuffix("readonly").tag("mcp-readonly-v1").version(1)
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                // Ignore callbacks delivered after Quit/unbind or generation change.
                if (connection === this) {
                    remote = if (binder?.pingBinder() == true)
                        IReadOnlyDiagnostics.Stub.asInterface(binder) else null
                    bindingStatus = if (remote != null) "service_connected"
                        else "dead_service_binder"
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (connection === this) {
                    remote = null
                    connection = null
                    args = null
                    bindingStatus = "service_disconnected"
                }
            }
        }
        return try {
            args = request
            connection = callback
            bindingStatus = "binding"
            bindingStartedAtMs = SystemClock.elapsedRealtime()
            Shizuku.bindUserService(request, callback)
            true
        } catch (_: Exception) {
            connection = null
            args = null
            bindingStatus = "bind_request_failed"
            false
        }
    }

    suspend fun read(operation: Int): String? = withContext(Dispatchers.IO) {
        if (operation !in THERMAL..HIDDEN_SERVICE_PROBE || !isReady()) return@withContext null
        runCatching {
            val service = remote ?: return@runCatching null
            check(service.remoteUid() in setOf(0, 2000))
            service.read(operation).take(196608)
        }.getOrNull()
    }

    /** Shizuku server died; callbacks from its previous generation are obsolete. */
    @Synchronized fun onBinderLost() {
        remote = null
        connection = null
        args = null
        bindingStatus = "server_disconnected"
    }

    /** Applies the opt-out immediately; a re-enable binds only when authorized. */
    fun updateEnabled(context: Context, enabled: Boolean) {
        ShizukuPreferences.setEnabled(context, enabled)
        if (enabled) bindIfAuthorized(context) else {
            enabledForProcess = false
            shutdown()
        }
    }

    @Synchronized fun shutdown() {
        remote = null
        val request = args
        val callback = connection
        args = null
        connection = null
        bindingStatus = "not_requested"
        bindingStartedAtMs = 0L
        if (request != null && callback != null) {
            runCatching { Shizuku.unbindUserService(request, callback, true) }
        }
    }
}
