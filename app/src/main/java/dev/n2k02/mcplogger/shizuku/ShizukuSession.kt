package dev.n2k02.mcplogger.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** The server is owned by LocalADB; clients only hold scoped Shizuku Binder access. */
enum class ShizukuPhase { IDLE, CONNECTING, VERIFIED, NEEDS_SETUP, STOPPED }
data class ShizukuState(
    val phase: ShizukuPhase = ShizukuPhase.IDLE,
    val message: String = "Shizuku not connected",
    val verifiedUid: Int? = null,
    val lastVerifiedAt: Long = 0L,
) {
    val connected: Boolean get() =
        phase == ShizukuPhase.VERIFIED && verifiedUid == Process.SHELL_UID
}
interface ShizukuPreferences {
    fun manuallyStopped(): Boolean
    fun setManuallyStopped(stopped: Boolean)
    fun lastVerifiedAt(): Long
    fun setLastVerifiedAt(timeMs: Long)
}
object ShizukuSessionRegistry {
    @Volatile private var current: ShizukuSession? = null
    fun install(core: ShizukuSession) { current = core }
    fun uninstall(core: ShizukuSession) { if (current === core) current = null }
}

/** A Shizuku user service is owned by the requesting app, authorized per package.
 * The Binder is never exported as an ordinary Android Service. */
private class ShizukuBridge(context: Context) {
    private val app = context.applicationContext
    private val args = Shizuku.UserServiceArgs(
        ComponentName(app, ShizukuShellService::class.java)
    ).daemon(false)
    private val lock = Mutex()
    @Volatile private var binder: IBinder? = null
    @Volatile private var pending: CompletableDeferred<IBinder>? = null
    @Volatile private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            pending?.complete(service)
        }
        override fun onServiceDisconnected(name: ComponentName) { binder = null }
    }

    private fun authorized(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.getUid() == Process.SHELL_UID &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    suspend fun connect(): Boolean = lock.withLock {
        if (!authorized()) return@withLock false
        if (binder?.isBinderAlive != true) {
            val deferred = CompletableDeferred<IBinder>()
            pending = deferred
            try {
                Shizuku.bindUserService(args, connection)
                bound = true
                val received = withTimeoutOrNull(12_000L) { deferred.await() }
                if (received == null || !received.isBinderAlive) {
                    disconnectLocked()
                    return@withLock false
                }
            } catch (_: Exception) {
                disconnectLocked()
                return@withLock false
            } finally {
                pending = null
            }
        }
        runCatching { execute("id -u", 3_000L).trim() == "2000" }
            .getOrDefault(false).also { if (!it) disconnectLocked() }
    }

    suspend fun execute(command: String, timeoutMs: Long): String {
        check(authorized()) { "Shizuku authorization or shell UID 2000 unavailable" }
        val service = binder?.takeIf { it.isBinderAlive }
            ?: error("Shizuku user service disconnected")
        require(command.length in 1..ShizukuShellService.MAX_COMMAND_CHARS)
        return withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ShizukuShellService.DESCRIPTOR)
                data.writeString(command)
                data.writeLong(timeoutMs)
                check(service.transact(ShizukuShellService.TRANSACTION_EXEC, data, reply, 0)) {
                    "Shizuku command transport failed"
                }
                reply.readException()
                checkNotNull(reply.readString()) { "Shizuku command returned no output" }
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private fun disconnectLocked() {
        binder = null
        if (bound) {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            bound = false
        }
    }
    suspend fun close() = lock.withLock { disconnectLocked() }
}

/** Every request validates live permission, Binder health and shell UID 2000. */
class ShizukuSession(context: Context, private val preferences: ShizukuPreferences) {
    private val bridge = ShizukuBridge(context)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ShizukuState(lastVerifiedAt = preferences.lastVerifiedAt()))
    val state: StateFlow<ShizukuState> = _state.asStateFlow()
    @Volatile private var quit = false
    private var owners = 0
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        if (!quit && !preferences.manuallyStopped() && owners > 0)
            scope.launch { retry(auto = true) }
    }
    private val binderDied = Shizuku.OnBinderDeadListener {
        _state.value = ShizukuState(phase = ShizukuPhase.NEEDS_SETUP,
            message = "Shizuku server stopped; reconnect in LocalADB")
    }
    private val permissionChanged = Shizuku.OnRequestPermissionResultListener { _, _ ->
        if (!quit && !preferences.manuallyStopped()) scope.launch { retry(auto = true) }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDied)
        Shizuku.addRequestPermissionResultListener(permissionChanged)
    }

    @Synchronized fun acquireService() {
        if (!quit) { owners++; scope.launch { retry(auto = true) } }
    }
    @Synchronized fun releaseService() { owners = (owners - 1).coerceAtLeast(0) }

    suspend fun retry(auto: Boolean = false): Boolean = lock.withLock {
        if (quit || (auto && preferences.manuallyStopped())) return@withLock false
        if (!auto) preferences.setManuallyStopped(false)
        _state.value = ShizukuState(phase = ShizukuPhase.CONNECTING,
            message = "Connecting to LocalADB Shizuku…")
        if (!bridge.connect()) {
            _state.value = ShizukuState(phase = ShizukuPhase.NEEDS_SETUP,
                message = "Start LocalADB and grant this app access")
            return@withLock false
        }
        val now = System.currentTimeMillis()
        _state.value = ShizukuState(phase = ShizukuPhase.VERIFIED,
            message = "Shizuku ready · shell UID 2000",
            verifiedUid = Process.SHELL_UID, lastVerifiedAt = now)
        preferences.setLastVerifiedAt(now)
        true
    }

    suspend fun execVerified(command: String, timeoutMs: Long = 15_000L): String = lock.withLock {
        check(!quit && !preferences.manuallyStopped()) { "Shizuku stopped" }
        check(_state.value.connected) { "Shizuku shell not verified" }
        val uid = try {
            bridge.execute("id -u", 3_000L).trim()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _state.value = ShizukuState(phase = ShizukuPhase.NEEDS_SETUP,
                message = "Shizuku shell connection lost")
            bridge.close()
            throw failure
        }
        if (uid != "2000") {
            _state.value = ShizukuState(phase = ShizukuPhase.NEEDS_SETUP,
                message = "Shizuku shell identity failed verification")
            bridge.close()
            throw SecurityException("Shizuku is not shell UID 2000")
        }
        bridge.execute(command, timeoutMs)
    }

    suspend fun disconnectIdle() = lock.withLock {
        bridge.close()
        if (!quit) _state.value = ShizukuState(message = "Shizuku session released")
    }

    suspend fun quit() {
        quit = true
        preferences.setManuallyStopped(true)
        lock.withLock {
            bridge.close()
            _state.value = ShizukuState(phase = ShizukuPhase.STOPPED, message = "Stopped by user")
        }
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDied)
        Shizuku.removeRequestPermissionResultListener(permissionChanged)
        scope.cancel()
    }
}
