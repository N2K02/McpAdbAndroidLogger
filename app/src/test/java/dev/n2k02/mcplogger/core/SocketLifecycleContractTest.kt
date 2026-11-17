package dev.n2k02.mcplogger.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SocketLifecycleContractTest {
    private fun source(file: String): String {
        val path = "app/src/main/java/dev/n2k02/mcplogger/" + file
        return listOf(File(path), File("../" + path)).first { it.isFile }.readText()
    }

    @Test fun localMcpClosesAcceptedClientsAndRejectsStaleListener() {
        val s = source("core/LocalMcpServer.kt")
        assertTrue(s.contains("clients.register(generation, client)"))
        assertTrue(s.contains("clients.isCurrent(generation)"))
        assertTrue(s.contains("clients.release(client)"))
        assertTrue(s.contains("clients.stop()"))
        assertTrue(s.contains("runCatching { client.close() }"))
        assertTrue(s.contains("workers?.shutdownNow()"))
        assertTrue(s.contains("val workerPool = newWorkers().also { workers = it }"))
        assertTrue(s.contains("ThreadPoolExecutor("))
        assertTrue(s.contains("6, 6, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(16)"))
        assertTrue(s.contains("ThreadPoolExecutor.AbortPolicy()"))
        assertFalse(s.contains("Executors.newFixedThreadPool(4)"))
    }

    @Test fun quitAwaitsAdbCloseBeforeServiceDestroy() {
        val s = source("core/LogMonitorService.kt")
        val quit = s.substringAfter("QUIT -> {").substringBefore("STOP -> {")
        assertTrue(quit.contains("quitJob = scope.launch"))
        assertTrue(quit.contains("withContext(NonCancellable) { AdbCoordinator.quit() }"))
        assertTrue(quit.contains("stopSelf()"))
        assertTrue(quit.indexOf("AdbCoordinator.quit()") < quit.indexOf("stopSelf()"))
        assertTrue(s.contains("if (quitJob?.isActive == true) return START_NOT_STICKY"))
        assertFalse(quit.contains("scope.launch { AdbCoordinator.quit() }"))
    }

    @Test fun tunnelRestartWaitsForOldProcessAndAlwaysCleansUp() {
        val service = source("core/LogMonitorService.kt")
        val restart = service.substringAfter("TUNNEL_RESTART -> {")
            .substringBefore("LOCAL_START, START -> {")
        assertTrue(restart.contains("previousTunnel?.join()"))
        assertTrue(restart.indexOf("previousTunnel?.join()") <
            restart.indexOf("QuickMcpTunnel.start(applicationContext)"))
        val tunnel = source("core/QuickMcpTunnel.kt")
        assertTrue(tunnel.contains("running.waitFor(2, TimeUnit.SECONDS)"))
        assertTrue(tunnel.contains("runCatching { running.outputStream.close() }"))
        assertTrue(tunnel.contains("if (process === running) process = null"))
    }

    @Test fun shizukuBinderShutdownReleasesConnectionAndResetsStatus() {
        val transport = source("adb/AdbTransport.kt")
        val coordinator = source("adb/AdbCoordinator.kt")
        val adapter = source("adb/ShizukuHostAdapter.kt")
        val shizuku = source("shizuku/ShizukuSession.kt")
        assertTrue(transport.contains("val lastKnownConnected: Boolean get() = isConnected"))
        assertTrue(transport.contains("suspend fun disconnect()"))
        assertTrue(transport.contains("ShizukuHostAdapter.current()?.disconnectIdle()"))
        assertTrue(transport.contains("suspend fun close() = disconnect()"))
        assertTrue(transport.contains("core.execVerified(command, timeoutMs)"))
        assertTrue(coordinator.contains("verified.value = state.connected"))
        assertTrue(coordinator.contains("ShizukuHostAdapter.shutdown()"))
        assertFalse(coordinator.contains("AdbTransport.close()"))
        assertTrue(adapter.contains("ShizukuSessionRegistry.uninstall(it)"))
        assertTrue(adapter.contains("it.quit()"))
        assertTrue(shizuku.contains("Shizuku.unbindUserService(args, connection, true)"))
        assertTrue(shizuku.contains("Shizuku.removeBinderDeadListener(binderDied)"))
        assertTrue(shizuku.contains("Shizuku.removeBinderReceivedListener(binderReceived)"))
        assertTrue(shizuku.contains("binder = null"))
        assertTrue(shizuku.contains("ShizukuPhase.NEEDS_SETUP"))
        assertFalse(transport.contains("LibAdbWire"))
        assertFalse(transport.contains("oldManager?.disconnect()"))
        assertFalse(source("MainActivity.kt").contains(
            "lifecycleScope.launch { AdbCoordinator.quit() }"))
    }
}
