package dev.n2k02.mcplogger.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ShizukuIntegrationContractTest {
    private fun source(relative: String): String {
        val root = listOf(File("app"), File("../app")).first { it.isDirectory }
        return root.resolve("src/main/java/dev/n2k02/mcplogger/" + relative).readText()
    }

    @Test fun shizukuIsOptionalAndNeverRequestsPermissionFromBackground() {
        val status = source("adb/ShizukuAvailability.kt")
        val app = source("MainActivity.kt")
        val ui = source("LoggerUi.kt")
        assertTrue(status.contains("Shizuku.checkSelfPermission()"))
        assertTrue(status.contains("fun requestPermission()"))
        assertTrue(ui.contains("Authorize Shizuku"))
        assertTrue(app.contains("ShizukuReadBackend.bindIfAuthorized(this)"))
        assertFalse(source("core/LocalMcpServer.kt").contains("requestPermission("))
    }

    @Test fun remoteServiceAcceptsOnlyFixedReadsAndExcludesWrites() {
        val src = source("shizuku/ReadOnlyShizukuService.kt")
        assertTrue(src.contains("when (operation)"))
        assertTrue(src.contains("Unknown diagnostic operation"))
        assertTrue(src.contains("Process.myUid()"))
        assertTrue(src.contains("serviceAvailability()"))
        assertFalse(src.contains("setSystemProperty"))
        assertFalse(src.contains("transactRemote"))
        assertTrue(src.contains("when (operation) {"))
    }

    @Test fun guardedBackendFallsBackToSharedAdbAndQuitUnbinds() {
        val src = source("shizuku/ShizukuReadBackend.kt")
        val diag = source("core/OnDemandSystemDiagnostics.kt")
        val quit = source("core/LogMonitorService.kt")
        assertTrue(src.contains("ShizukuAvailability.state().usable"))
        assertTrue(src.contains("Shizuku.unbindUserService"))
        assertTrue(diag.contains("alternative ?: AdbTransport.exec"))
        assertTrue(diag.contains("execution_backend"))
        assertTrue(quit.contains("ShizukuReadBackend.shutdown()"))
    }
}
