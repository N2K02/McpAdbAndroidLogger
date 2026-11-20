package dev.n2k02.mcplogger.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Enforce Shizuku-only privilege transport. The ADB label in Settings is
 * intentional; legacy adb-core code and dependency must never return.
 */
class ShizukuOnlyTransportContractTest {
    private fun root(): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            if (File(current, "settings.gradle.kts").exists()) return current
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate Android repository root")
    }

    @Test fun externalAdbCoreLibraryIsAbsent() {
        val root = root()
        root.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".gradle.kts") || it.name == "gradle.properties") }
            .forEach { file ->
                val contents = file.readText()
                assertFalse("${file.path} still references adb-core library",
                    contents.contains("dev.n2k02:adb-core"))
            }
    }

    @Test fun legacyCoreSourceIsAbsent() {
        val root = root()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" &&
                it.path.replace('\\', '/').contains("/src/main/") }
            .forEach { file ->
                val contents = file.readText()
                assertFalse("${file.path} imports the removed shared ADB core",
                    contents.contains("dev.n2k02.adb.core"))
            }
    }
}
