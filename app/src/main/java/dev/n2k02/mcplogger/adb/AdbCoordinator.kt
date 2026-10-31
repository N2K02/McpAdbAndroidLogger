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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Socket
import java.net.InetSocketAddress

/**
 * Independent same-device ADB controller. A TLS port is a discovery hint, NOT authorization:
 * every connection is authenticated and validated using id -u == 2000.
 */
object AdbCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile var connectPort: Int? = null
        private set
    @Volatile var pairingPort: Int? = null
        private set
    @Volatile private var discovery: NsdManager? = null
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun discover(context: Context) {
        if (discovery != null) return
        val manager = context.applicationContext.getSystemService(NsdManager::class.java) ?: return
        discovery = manager
        listOf("_adb-tls-connect._tcp.", "_adb-tls-pairing._tcp.").forEach { service ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                    MonitorState.error("ADB discovery failed: " + errorCode)
                }
                override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                @Suppress("DEPRECATION")
                override fun onServiceFound(info: NsdServiceInfo) {
                    manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            // We connect only to loopback; service host is never used.
                            val port = serviceInfo.port.takeIf { it in 1..65535 } ?: return
                            if (service.contains("pairing")) pairingPort = port else connectPort = port
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
    }

    suspend fun pair(context: Context, port: Int, code: String): Result<Unit> = mutex.withLock {
        if (port !in 1..65535 || !code.matches(Regex("[0-9]{6}"))) {
            return@withLock Result.failure(IllegalArgumentException("Enter a valid six-digit Android pairing code and port"))
        }
        AdbTransport.pairLocal(context, port, code).onFailure {
            MonitorState.error("Pairing failed: " + (it.message ?: "unknown"))
        }
    }

    suspend fun connect(context: Context, preferred: Int? = null): Result<Unit> = mutex.withLock {
        val app = context.applicationContext
        val candidates = listOfNotNull(
            5555.takeIf { tcpListening() },
            preferred?.takeIf { it in 1..65535 },
            connectPort
        ).distinct()
        var lastError: Throwable = IllegalStateException("No local ADB port. Enable Wireless debugging and Pair device.")
        for (port in candidates) {
            try {
                AdbTransport.close()
                if (port == 5555) AdbTransport.connect(app, AdbTransport.LOOPBACK, port).getOrThrow()
                else AdbTransport.connectLocalTls(app, port).getOrThrow()
                val output = AdbTransport.exec("id -u").getOrThrow().trim()
                check(output == "2000") { "ADB shell identity was " + output + " (expected UID 2000)" }
                MonitorState.adb.value = "UID 2000 verified • 127.0.0.1:" + port
                if (port != 5555 && prefs(app).getBoolean("tcp_mode", true)) {
                    AdbTransport.switchToTcpip().getOrThrow()
                    delay(1500)
                    AdbTransport.close()
                    AdbTransport.connect(app, AdbTransport.LOOPBACK, 5555).getOrThrow()
                    check(AdbTransport.exec("id -u").getOrThrow().trim() == "2000")
                    MonitorState.adb.value = "UID 2000 verified • TCP/5555"
                }
                return@withLock Result.success(Unit)
            } catch (failure: Exception) {
                lastError = failure
                MonitorState.error("ADB connection: " + (failure.message ?: "failed"))
            }
        }
        MonitorState.adb.value = "Disconnected"
        Result.failure(lastError)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("mcp_logger", Context.MODE_PRIVATE)
    fun tcpEnabled(context: Context) = prefs(context).getBoolean("tcp_mode", true)
    fun setTcpEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("tcp_mode", value).apply()
    }
    fun autoStart(context: Context) = prefs(context).getBoolean("start_on_boot", true)
    fun setAutoStart(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("start_on_boot", value).apply()
    }
    private fun tcpListening(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", 5555), 500); true }
    }.getOrDefault(false)
    fun connectAsync(context: Context) {
        scope.launch { connect(context).onFailure { MonitorState.adb.value = it.message ?: "ADB unavailable" } }
    }
    suspend fun quit() {
        stopDiscovery()
        mutex.withLock { AdbTransport.close() }
        MonitorState.adb.value = "Stopped"
    }
}
