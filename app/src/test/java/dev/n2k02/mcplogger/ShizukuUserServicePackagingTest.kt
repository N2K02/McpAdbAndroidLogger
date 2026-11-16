package dev.n2k02.mcplogger

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Shizuku resolves the UserService by the original class name in another
 * process. A green debug APK alone cannot catch R8 stripping its constructor.
 * LocalADB uses equivalent -keep rules for its privileged services.
 */
class ShizukuUserServicePackagingTest {
    private val app = listOf(File("app"), File("../app")).first { it.isDirectory }
    private fun source(path: String) = app.resolve(path).readText()

    @Test fun minifiedReleaseKeepsReflectiveUserServiceAndBinderStub() {
        val rules = source("proguard-rules.pro")
        assertTrue(rules.contains(
            "-keep class dev.n2k02.mcplogger.shizuku.ReadOnlyShizukuService { *; }"))
        assertTrue(rules.contains(
            "-keep class dev.n2k02.mcplogger.shizuku.IReadOnlyDiagnostics"))
        assertTrue(source("src/main/java/dev/n2k02/mcplogger/shizuku/ShizukuReadBackend.kt")
            .contains("ReadOnlyShizukuService::class.java"))
    }

    @Test fun bindingDoesNotStallForeverWithoutCallback() {
        val backend = source(
            "src/main/java/dev/n2k02/mcplogger/shizuku/ShizukuReadBackend.kt")
        assertTrue(backend.contains("binding_timeout"))
        assertTrue(backend.contains("15_000L"))
        assertTrue(backend.contains("Shizuku.unbindUserService("))
        assertTrue(backend.contains("diagnosticStatus()"))
        val capabilities = source(
            "src/main/java/dev/n2k02/mcplogger/adb/ShizukuAvailability.kt")
        assertTrue(capabilities.contains("user_service_lifecycle"))
        assertTrue(capabilities.contains("connection_diagnostic"))
    }
}
