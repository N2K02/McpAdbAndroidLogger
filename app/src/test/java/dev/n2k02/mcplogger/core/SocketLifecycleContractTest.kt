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

    @Test fun adbShellClosesAllStreamsAndDisconnectionResetsStatus() {
        val adb = source("adb/AdbTransport.kt")
        val close = adb.substringAfter("fun close() {").substringBefore("private companion object")
        assertTrue(close.contains("runCatching { reader.close() }"))
        assertTrue(close.contains("runCatching { out.close() }"))
        assertTrue(adb.contains("lastKnownConnected = false"))
        assertTrue(source("adb/AdbCoordinator.kt").contains("AdbTransport.close()"))
    }
}
