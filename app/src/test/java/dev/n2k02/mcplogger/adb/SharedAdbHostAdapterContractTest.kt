package dev.n2k02.mcplogger.adb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedAdbHostAdapterContractTest {
    @Test fun sharedAdbCoreIsPinnedWithoutLosingOriginalAuthorization() {
        val root = listOf(File("app"), File("../app")).first { it.isDirectory }
        val settings = root.resolve("../settings.gradle.kts").readText()
        val build = root.resolve("build.gradle.kts").readText()
        val src = root.resolve("src/main/java/dev/n2k02/mcplogger/adb/ShizukuHostAdapter.kt").readText()
        assertTrue(!settings.contains("n2k02AdbCoreLibrary"))
        assertTrue(!build.contains("dev.n2k02:adb-core:"))
        assertTrue(build.contains("dev.rikka.shizuku:api:13.1.5"))
        assertTrue(src.contains("ShizukuSession("))
        assertTrue(src.contains("AdbRecoverySettings."))
        assertTrue(src.contains("manuallyStopped()"))
        assertTrue(!src.contains("LibAdbWire"))
        assertTrue(src.contains("ShizukuSessionRegistry.install(it)"))
        assertTrue(src.contains("suspend fun shutdown()"))
        assertTrue(src.contains("it.quit()"))
    }
}
