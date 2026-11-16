package dev.n2k02.mcplogger.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import dev.n2k02.mcplogger.adb.ShizukuAvailability
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
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null

    fun isReady(): Boolean = ShizukuAvailability.state().usable &&
        remote?.asBinder()?.pingBinder() == true

    @Synchronized fun bindIfAuthorized(context: Context): Boolean {
        if (!ShizukuAvailability.state().usable) return false
        if (isReady()) return true
        if (connection != null) return false
        val request = Shizuku.UserServiceArgs(
            ComponentName(context.applicationContext, ReadOnlyShizukuService::class.java))
            .daemon(false).processNameSuffix("readonly").tag("mcp-readonly-v1").version(1)
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote = IReadOnlyDiagnostics.Stub.asInterface(binder)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
            }
        }
        return try {
            args = request
            connection = callback
            Shizuku.bindUserService(request, callback)
            true
        } catch (_: Exception) {
            connection = null
            args = null
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

    @Synchronized fun shutdown() {
        remote = null
        val request = args
        val callback = connection
        args = null
        connection = null
        if (request != null && callback != null) {
            runCatching { Shizuku.unbindUserService(request, callback, true) }
        }
    }
}
