package dev.n2k02.adb.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/** Compatibility interface while clients migrate away from historical ADB UI names.
 * This module contains NO ADB sockets, TLS pairing, mDNS, or TCP/5555 fallback. */
enum class AdbOption { AUTO_PAIR, WATCHDOG, START_ON_BOOT, RESTORE_AFTER_RESTART, TCP_5555 }
enum class AdbTransportMode { NONE, WIRELESS_TLS, LOCAL_TCP_5555, SHIZUKU }
enum class AdbPhase { IDLE, SEARCHING, PAIRING, CONNECTING, VERIFIED, NEEDS_SETUP, STOPPED }
data class AdbState(
    val phase: AdbPhase = AdbPhase.IDLE,
    val message: String = "Shizuku not connected",
    val verifiedUid: Int? = null,
    val transport: AdbTransportMode = AdbTransportMode.NONE,
    val pairingPort: Int? = null,
    val connectionPort: Int? = null,
    val scanning: Boolean = false,
    val lastVerifiedAt: Long = 0L,
) {
    val connected: Boolean get() = phase == AdbPhase.VERIFIED && verifiedUid == Process.SHELL_UID
}
interface AdbPreferenceStore {
    fun enabled(option: AdbOption): Boolean
    fun setEnabled(option: AdbOption, enabled: Boolean)
    fun manuallyStopped(): Boolean
    fun setManuallyStopped(stopped: Boolean)
    fun lastTlsPort(): Int?
    fun setLastTlsPort(port: Int)
    fun lastVerifiedAt(): Long
    fun setLastVerifiedAt(timeMs: Long)
    fun wasAuthorized(): Boolean
    fun setAuthorized()
}
interface AdbHostHooks {
    suspend fun onVerifiedShell() {}
    suspend fun onRestoreAfterRestartChanged(enabled: Boolean) {}
}
object AdbCoreRegistry {
    @Volatile private var current: AdbCore? = null
    fun install(core: AdbCore) { current = core }
    fun uninstall(core: AdbCore) { if (current === core) current = null }
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

/** Shell verification is performed after each bind AND before every command. */
class AdbCore(
    context: Context,
    val preferences: AdbPreferenceStore,
    private val hooks: AdbHostHooks = object : AdbHostHooks {},
) {
    private val bridge = ShizukuBridge(context)
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AdbState(lastVerifiedAt = preferences.lastVerifiedAt()))
    val state: StateFlow<AdbState> = _state.asStateFlow()
    @Volatile private var quit = false
    private var pages = 0
    private var services = 0
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        if (!quit && !preferences.manuallyStopped() && (pages > 0 || services > 0))
            scope.launch { retry(auto = true) }
    }
    private val binderDied = Shizuku.OnBinderDeadListener {
        _state.value = AdbState(phase = AdbPhase.NEEDS_SETUP,
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
    @Synchronized fun enterPage() { if (!quit) { pages++; scope.launch { retry(auto = true) } } }
    @Synchronized fun leavePage() { pages = (pages - 1).coerceAtLeast(0) }
    @Synchronized fun acquireService() { if (!quit) { services++; scope.launch { retry(auto = true) } } }
    @Synchronized fun releaseService() { services = (services - 1).coerceAtLeast(0) }
    fun beginAutomaticPairingDiscovery() { /* Shizuku has no ADB pairing flow. */ }
    fun endAutomaticPairingDiscovery() { }
    suspend fun pair(port: Int, code: String): Boolean = false
    suspend fun connectTls(port: Int): Boolean = retry(auto = true)

    suspend fun retry(auto: Boolean = false): Boolean = lock.withLock {
        if (quit || (auto && preferences.manuallyStopped())) return@withLock false
        if (!auto) preferences.setManuallyStopped(false)
        _state.value = _state.value.copy(phase = AdbPhase.CONNECTING,
            message = "Connecting to LocalADB Shizuku…", verifiedUid = null,
            transport = AdbTransportMode.NONE)
        if (!bridge.connect()) {
            _state.value = _state.value.copy(phase = AdbPhase.NEEDS_SETUP,
                message = "Start LocalADB Shizuku and grant this app access")
            return@withLock false
        }
        val now = System.currentTimeMillis()
        _state.value = AdbState(phase = AdbPhase.VERIFIED,
            message = "Shizuku ready • shell UID 2000", verifiedUid = Process.SHELL_UID,
            transport = AdbTransportMode.SHIZUKU, lastVerifiedAt = now)
        preferences.setLastVerifiedAt(now)
        preferences.setAuthorized()
        scope.launch {
            runCatching { hooks.onVerifiedShell() }
            if (preferences.enabled(AdbOption.RESTORE_AFTER_RESTART))
                runCatching { hooks.onRestoreAfterRestartChanged(true) }
        }
        true
    }

    suspend fun execVerified(command: String, timeoutMs: Long = 15_000L): String = lock.withLock {
        check(!quit && !preferences.manuallyStopped()) { "Shizuku stopped" }
        check(_state.value.connected) { "Shizuku shell not verified" }
        val uid = runCatching { bridge.execute("id -u", 3_000L).trim() }.getOrNull()
        if (uid != "2000") {
            _state.value = AdbState(phase = AdbPhase.NEEDS_SETUP,
                message = "Shizuku shell identity failed verification")
            bridge.close()
            throw SecurityException("Shizuku is not shell UID 2000")
        }
        bridge.execute(command, timeoutMs)
    }
    suspend fun setOption(option: AdbOption, enabled: Boolean) {
        if (quit || option == AdbOption.AUTO_PAIR || option == AdbOption.TCP_5555) return
        preferences.setEnabled(option, enabled)
        if (option == AdbOption.RESTORE_AFTER_RESTART && state.value.connected)
            hooks.onRestoreAfterRestartChanged(enabled)
    }
    suspend fun disableTcp(): Boolean = false // No device-wide ADB TCP switch.
    suspend fun startAfterBoot(): Boolean =
        !preferences.manuallyStopped() && preferences.enabled(AdbOption.START_ON_BOOT) &&
            retry(auto = true)
    suspend fun disconnectIdle() = lock.withLock {
        bridge.close()
        if (!quit) _state.value = AdbState(message = "Shizuku session released")
    }
    suspend fun stop() = lock.withLock {
        preferences.setManuallyStopped(true)
        bridge.close()
        _state.value = AdbState(phase = AdbPhase.STOPPED, message = "Stopped by user")
    }
    suspend fun quit() {
        quit = true
        stop()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDied)
        Shizuku.removeRequestPermissionResultListener(permissionChanged)
        scope.cancel()
    }
}
