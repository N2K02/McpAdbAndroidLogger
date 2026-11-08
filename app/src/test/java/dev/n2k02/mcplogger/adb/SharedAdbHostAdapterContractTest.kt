package dev.n2k02.mcplogger.adb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedAdbHostAdapterContractTest {
    @Test fun sharedAdbCoreIsPinnedWithoutLosingOriginalAuthorization() {
        val root = listOf(File("app"), File("../app")).first { it.isDirectory }
        val settings = root.resolve("../settings.gradle.kts").readText()
        val build = root.resolve("build.gradle.kts").readText()
        val src = root.resolve("src/main/java/dev/n2k02/mcplogger/adb/SharedAdbHostAdapter.kt").readText()
        assertTrue(settings.contains("n2k02AdbCoreLibrary"))
        assertTrue(build.contains("dev.n2k02:adb-core:0.1.7"))
        assertTrue(src.contains("AdbIdentity.getOrCreate(ctx)"))
        assertTrue(src.contains("AdbRecoverySettings."))
        assertTrue(src.contains("manuallyStopped()"))
        assertTrue(src.contains("LibAdbWire"))
        assertTrue(src.contains("AdbCoreRegistry.install(it)"))
        assertTrue(src.contains("suspend fun shutdown()"))
        assertTrue(src.contains("it.quit()"))
    }
}
