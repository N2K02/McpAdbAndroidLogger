package dev.n2k02.mcplogger.adb

import android.content.Context
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.AdbState
import dev.n2k02.adb.core.AdbTransportMode
import dev.n2k02.mcplogger.core.MonitorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/** Public status adapter for existing MCP UI and services. The shared library
 * owns pairing, discovery, verification, transport switching and watchdogs. */
data class AdbDiscoveryState(
    val pairingPort: Int? = null,
    val connectPort: Int? = null,
    val status: String = "Discovering Wireless debugging",
)

object AdbCoordinator {
    val discoveryState = MutableStateFlow(AdbDiscoveryState())
    val verified = MutableStateFlow(false)
    val tcpPortOpen = MutableStateFlow(false)
    val manualRetryRequired = MutableStateFlow(false)
    @Volatile var connectPort: Int? = null
        private set
    @Volatile var pairingPort: Int? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindingLock = Any()
    private var observedCore: AdbCore? = null
    private var observer: Job? = null
    private var serviceOwner = false

    private fun core(context: Context): AdbCore {
        val shared = SharedAdbHostAdapter.core(context.applicationContext)
        synchronized(bindingLock) {
            if (observedCore !== shared) {
                observer?.cancel()
                observedCore = shared
                publish(shared.state.value)
                observer = scope.launch {
                    shared.state.collect { publish(it) }
                }
            }
        }
        return shared
    }

    private fun publish(state: AdbState) {
        val oldPairingPort = pairingPort
        pairingPort = state.pairingPort
        connectPort = state.connectionPort
        verified.value = state.connected
        // Shared manual Stop drops every owner. Clear our mirrored ownership
        // so a subsequent explicit Retry can reacquire background recovery.
        if (state.phase == dev.n2k02.adb.core.AdbPhase.STOPPED) {
            synchronized(this) { serviceOwner = false }
        }
        tcpPortOpen.value = state.connected &&
            state.transport == AdbTransportMode.LOCAL_TCP_5555
        discoveryState.value = AdbDiscoveryState(
            pairingPort = state.pairingPort,
            connectPort = state.connectionPort,
            status = state.message,
        )
        MonitorState.adb.value = if (state.connected) {
            "UID 2000 verified • " +
                if (state.transport == AdbTransportMode.LOCAL_TCP_5555) "TCP/5555"
                else "Wireless debugging TLS"
        } else state.message
        if (state.pairingPort != null && state.pairingPort != oldPairingPort)
            PairingAccessibilityService.onPairingPortDiscovered()
    }

    /** The MCP service owns shared recovery independently from Activity visibility.
     * Repeated Activity/WorkManager starts never acquire a second service owner. */
    @Synchronized fun discover(context: Context) {
        if (AdbRecoverySettings.manuallyQuit(context)) return
        val shared = core(context)
        if (!serviceOwner) {
            serviceOwner = true
            shared.acquireService()
        }
    }

    @Synchronized fun stopDiscovery() {
        if (serviceOwner) {
            observedCore?.releaseService()
            serviceOwner = false
        }
    }

    suspend fun connect(context: Context, preferred: Int? = null,
                        userInitiated: Boolean = false): Result<Unit> {
        val app = context.applicationContext
        if (AdbRecoverySettings.manuallyQuit(app) && !userInitiated)
            return Result.failure(IllegalStateException("Stopped by Quit"))
        return try {
            if (userInitiated) {
                AdbRecoverySettings.setManuallyQuit(app, false)
                manualRetryRequired.value = false
            }
            val shared = core(app)
            var connected = shared.retry(auto = !userInitiated)
            if (!connected && preferred != null && preferred in 1..65535)
                connected = shared.connectTls(preferred)
            check(connected && shared.state.value.connected) {
                shared.state.value.message
            }
            publish(shared.state.value)
            if (userInitiated) {
                // A previous manual Stop cleared service ownership in AdbCore.
                // Retain recovery after leaving Settings, not just during retry.
                discover(app)
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            verified.value = false
            MonitorState.error("ADB connection: " + (failure.message ?: "failed"))
            Result.failure(failure)
        }
    }

    fun connectAsync(context: Context) {
        if (AdbRecoverySettings.manuallyQuit(context)) return
        scope.launch { connect(context) }
    }

    fun tcpEnabled(context: Context) = AdbRecoverySettings.tcpMode(context)
    fun setTcpEnabled(context: Context, value: Boolean) =
        AdbRecoverySettings.setTcpMode(context, value)
    fun autoStart(context: Context) = AdbRecoverySettings.startOnBoot(context)
    fun setAutoStart(context: Context, value: Boolean) =
        AdbRecoverySettings.setStartOnBoot(context, value)

    suspend fun disableTcp(context: Context): Result<Unit> {
        return try {
            val shared = core(context)
            check(shared.state.value.connected) {
                "Verify shell UID 2000 before disabling TCP/5555"
            }
            check(shared.disableTcp()) {
                "Could not confirm TCP/5555 shutdown"
            }
            delay(650)
            check(!isTcpListening()) {
                "TCP/5555 is still listening; confirm Android debugging settings"
            }
            publish(shared.state.value)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    fun isTcpListening(): Boolean = runCatching {
        Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", 5555), 500)
        }
        true
    }.getOrDefault(false)

    suspend fun quit() {
        stopDiscovery()
        SharedAdbHostAdapter.shutdown()
        synchronized(bindingLock) {
            observer?.cancel()
            observer = null
            observedCore = null
        }
        scope.coroutineContext.cancelChildren()
        verified.value = false
        tcpPortOpen.value = false
        manualRetryRequired.value = false
        pairingPort = null
        connectPort = null
        MonitorState.adb.value = "Stopped"
        discoveryState.value = AdbDiscoveryState(status = "Stopped")
    }
}
