package dev.n2k02.mcplogger.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard the fixed libadb device-name callback against release-only recursion. */
class AdbDeviceNameRegressionTest {
    private fun root() =
        generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { it.resolve("app/src/main/java/dev/n2k02/mcplogger/adb/SharedAdbHostAdapter.kt").isFile }

    @Test fun sharedLibraryPinIncludesFixedDeviceNameCallback() {
        val build = root().resolve("app/build.gradle.kts").readText()
        assertTrue(build.contains("dev.n2k02:adb-core:0.1.7"))
        assertFalse(build.contains("dev.n2k02:adb-core:0.1.4"))
        assertTrue(Class.forName("dev.n2k02.adb.core.LibAdbWire").name.endsWith("LibAdbWire"))
    }

    @Test fun activeSharedAdapterDoesNotOverrideItsOwnDeviceNameGetter() {
        val adapter = root().resolve("app/src/main/java/dev/n2k02/mcplogger/adb/SharedAdbHostAdapter.kt").readText()
        assertTrue(adapter.contains("LibAdbWire"))
        assertFalse(adapter.contains("override fun getDeviceName()"))
        val legacy = root().resolve("app/src/main/java/dev/n2k02/mcplogger/adb/AdbTransport.kt").readText()
        assertTrue(legacy.contains("override fun getDeviceName(): String = \"McpAdbAndroidLogger\""))
        assertFalse(legacy.contains("override fun getDeviceName(): String = deviceName"))
    }
}
