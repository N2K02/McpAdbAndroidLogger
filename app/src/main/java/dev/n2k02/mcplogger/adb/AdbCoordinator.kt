package dev.n2k02.mcplogger.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.n2k02.mcplogger.core.MonitorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Socket
import java.net.InetSocketAddress

/**
 * Independent same-device ADB controller. A TLS port is a discovery hint, NOT authorization:
 * every connection is authenticated and validated using id -u == 2000.
 */
data class AdbDiscoveryState(
    val pairingPort: Int? = null,
    val connectPort: Int? = null,
    val status: String = "Discovering Wireless debugging",
)

object AdbCoordinator {
    /** UI observes discovery just as VRec observes its Wireless ADB status. */
    val discoveryState = MutableStateFlow(AdbDiscoveryState())
    val verified = MutableStateFlow(false)
    val tcpPortOpen = MutableStateFlow(false)
    val manualRetryRequired = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile var connectPort: Int? = null
        private set
    @Volatile var pairingPort: Int? = null
        private set
    @Volatile private var discovery: NsdManager? = null
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    @Volatile private var lastDiscoveredAttempt: Int? = null

    fun discover(context: Context) {
        if (discovery != null || AdbRecoverySettings.manuallyQuit(context)) return
        val manager = context.applicationContext.getSystemService(NsdManager::class.java) ?: return
        discovery = manager
        listOf("_adb-tls-connect._tcp.", "_adb-tls-pairing._tcp.").forEach { service ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                    discoveryState.value = discoveryState.value.copy(status = "Discovery failed ($errorCode)")
                    MonitorState.error("ADB discovery failed: " + errorCode)
                }
                override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onServiceLost(info: NsdServiceInfo) {
                    val current = discoveryState.value
                    if (service.contains("pairing") && current.pairingPort == info.port) {
                        pairingPort = null
                        discoveryState.value = current.copy(pairingPort = null)
                    } else if (!service.contains("pairing") && current.connectPort == info.port) {
                        connectPort = null
                        discoveryState.value = current.copy(connectPort = null)
                    }
                }
                @Suppress("DEPRECATION")
                override fun onServiceFound(info: NsdServiceInfo) {
                    manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            // We connect only to loopback; service host is never used.
                            val port = serviceInfo.port.takeIf { it in 1..65535 } ?: return
                            if (service.contains("pairing")) {
                                pairingPort = port
                                discoveryState.value = discoveryState.value.copy(
                                    pairingPort = port, status = "Pairing code available")
                                PairingAccessibilityService.onPairingPortDiscovered()
                            } else {
                                connectPort = port
                                discoveryState.value = discoveryState.value.copy(
                                    connectPort = port, status = "Wireless ADB discovered")
                                if (!verified.value && !manualRetryRequired.value &&
                                    lastDiscoveredAttempt != port) {
                                    lastDiscoveredAttempt = port
                                    connectAsync(context.applicationContext)
                                }
                            }
                        }
                    })
                }
            }
            listeners.add(listener)
            runCatching { manager.discoverServices(service, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { MonitorState.error("mDNS: " + it.javaClass.simpleName) }
        }
    }

    fun stopDiscovery() {
        val manager = discovery ?: return
        listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        listeners.clear()
        discovery = null
        pairingPort = null
        connectPort = null
        discoveryState.value = AdbDiscoveryState(status = "Discovery stopped")
    }

    suspend fun pair(context: Context, port: Int, code: String): Result<Unit> = mutex.withLock {
        if (port !in 1..65535 || !code.matches(Regex("[0-9]{6}"))) {
            return@withLock Result.failure(IllegalArgumentException("Enter a valid six-digit Android pairing code and port"))
        }
        discoveryState.value = discoveryState.value.copy(status = "Pairing with Android")
        AdbTransport.pairLocal(context, port, code).also { result ->
            result.onSuccess {
                AdbRecoverySettings.rememberPaired(context)
                manualRetryRequired.value = false
                lastDiscoveredAttempt = null
                discoveryState.value = discoveryState.value.copy(status = "Paired; verifying shell")
            }.onFailure {
                MonitorState.error("Pairing failed: " + (it.message ?: "unknown"))
            }
        }
    }

    suspend fun connect(context: Context, preferred: Int? = null,
                        userInitiated: Boolean = false): Result<Unit> = mutex.withLock {
        val app = context.applicationContext
        if (AdbRecoverySettings.manuallyQuit(app) && !userInitiated)
            return@withLock Result.failure(IllegalStateException("Stopped by Quit"))
        if (manualRetryRequired.value && !userInitiated)
            return@withLock Result.failure(SecurityException("User authorization needed; tap Retry"))
        if (userInitiated) {
            manualRetryRequired.value = false
            lastDiscoveredAttempt = null
        }
        // Preserve an already-verified ADB session instead of replacing it
        // whenever the Activity opens or a recovery request overlaps.
        if (verified.value && AdbTransport.lastKnownConnected) {
            val existingUid = try {
                AdbTransport.exec("id -u", timeoutMs = 6_000).getOrNull()?.trim()
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            if (existingUid == "2000") {
                manualRetryRequired.value = false
                return@withLock Result.success(Unit)
            }
            verified.value = false
        }
        MonitorState.adb.value = "Connecting..."
        tcpPortOpen.value = tcpListening()
        val candidates = listOfNotNull(
            5555.takeIf { tcpListening() },
            preferred?.takeIf { it in 1..65535 },
            connectPort,
            AdbRecoverySettings.lastVerifiedTlsPort(app)
        ).distinct()
        var lastError: Throwable = IllegalStateException("No local ADB port. Enable Wireless debugging and Pair device.")
        verified.value = false
        for (port in candidates) {
            try {
                AdbTransport.close()
                if (port == 5555) AdbTransport.connect(app, AdbTransport.LOOPBACK, port).getOrThrow()
                else AdbTransport.connectLocalTls(app, port).getOrThrow()
                val output = AdbTransport.exec("id -u").getOrThrow().trim()
                check(output == "2000") { "ADB shell identity was " + output + " (expected UID 2000)" }
                verified.value = true
                if (port != 5555) AdbRecoverySettings.rememberVerifiedTls(app, port)
                MonitorState.adb.value = "UID 2000 verified • 127.0.0.1:" + port
                discoveryState.value = discoveryState.value.copy(status = "Authorized shell UID 2000")
                if (AdbRecoverySettings.restoreAfterRestart(app)) {
                    // Only an explicitly enabled preference may request this grant.
                    AdbRecoverySettings.grantSecureSettings(app)
                }
                if (port != 5555 && AdbRecoverySettings.tcpMode(app)) {
                    AdbTransport.switchToTcpip().getOrThrow()
                    delay(1500)
                    AdbTransport.close()
                    AdbTransport.connect(app, AdbTransport.LOOPBACK, 5555).getOrThrow()
                    check(AdbTransport.exec("id -u").getOrThrow().trim() == "2000")
                    MonitorState.adb.value = "UID 2000 verified • TCP/5555"
                    tcpPortOpen.value = true
                }
                tcpPortOpen.value = tcpListening()
                manualRetryRequired.value = false
                return@withLock Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastError = failure
                verified.value = false
                if (port != 5555) AdbRecoverySettings.clearVerifiedPort(app, port)
                MonitorState.error("ADB connection: " + (failure.message ?: "failed"))
                if (AdbRecoverySettings.requiresManualRetry(failure) && port != 5555) {
                    manualRetryRequired.value = true
                    break
                }
            }
        }
        // Even an unsuccessful TLS handshake may leave a transport open.
        AdbTransport.close()
        MonitorState.adb.value = "Disconnected"
        discoveryState.value = discoveryState.value.copy(
            status = if (manualRetryRequired.value) "Authorization requires manual Retry"
                else "ADB unavailable; checking discovery")
        Result.failure(lastError)
    }

    fun tcpEnabled(context: Context) = AdbRecoverySettings.tcpMode(context)
    fun setTcpEnabled(context: Context, value: Boolean) =
        AdbRecoverySettings.setTcpMode(context, value)
    fun autoStart(context: Context) = AdbRecoverySettings.startOnBoot(context)
    fun setAutoStart(context: Context, value: Boolean) =
        AdbRecoverySettings.setStartOnBoot(context, value)

    /** VRec parity: disabling TCP changes actual adbd mode, not just a toggle. */
    suspend fun disableTcp(context: Context): Result<Unit> = mutex.withLock {
        val app = context.applicationContext
        if (!verified.value) return@withLock Result.failure(
            SecurityException("Verify ADB shell UID 2000 before disabling TCP"))
        return@withLock try {
            if (tcpListening()) {
                AdbTransport.switchToUsb().getOrThrow()
                delay(650)
            }
            AdbTransport.close()
            verified.value = false
            tcpPortOpen.value = tcpListening()
            MonitorState.adb.value = "TCP/5555 disabled; reconnect with Wireless debugging"
            // Return success ONLY after probing the TCP listener.
            if (tcpListening()) Result.failure(IllegalStateException("TCP/5555 still listening"))
            else Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isTcpListening(): Boolean = tcpListening()
    private fun tcpListening(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 5555), 500); true }
    }.getOrDefault(false)
    fun connectAsync(context: Context) {
        if (AdbRecoverySettings.manuallyQuit(context) || manualRetryRequired.value) return
        scope.launch {
            connect(context).onFailure {
                if (!manualRetryRequired.value) MonitorState.adb.value = "Disconnected"
            }
        }
    }
    suspend fun quit() {
        stopDiscovery()
        scope.coroutineContext.cancelChildren()
        mutex.withLock { AdbTransport.close() }
        verified.value = false
        tcpPortOpen.value = false
        manualRetryRequired.value = false
        lastDiscoveredAttempt = null
        MonitorState.adb.value = "Stopped"
        discoveryState.value = AdbDiscoveryState(status = "Stopped")
    }
}
