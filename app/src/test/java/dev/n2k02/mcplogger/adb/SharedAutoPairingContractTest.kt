package dev.n2k02.mcplogger.adb

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SharedAutoPairingContractTest {
    private fun source(name: String): String {
        val dir = listOf(File("app/src/main/java/dev/n2k02/mcplogger"),
            File("src/main/java/dev/n2k02/mcplogger")).first { it.isDirectory }
        return dir.resolve(name).readText()
    }

    @Test fun onlySharedReaderHandlesSystemDialogAndPairingCodes() {
        val reader = source("adb/PairingAccessibilityService.kt")
        assertTrue(reader.contains("SharedAdbPairingAccessibilityService()"))
        assertTrue(reader.contains("SharedAdbHostAdapter.core(applicationContext)"))
        assertTrue(reader.contains("notifyPairingPortDiscovered()"))
        assertFalse(reader.contains("rootInActiveWindow"))
        assertFalse(reader.contains("extractCode("))
        assertFalse(reader.contains("AdbCoordinator.pair("))
        val coordinator = source("adb/AdbCoordinator.kt")
        assertFalse(coordinator.contains("suspend fun pair(context:"))
        val home = source("AdbHomeSetupCard.kt")
        assertTrue(home.contains("AdbHomeSetupPage(adb, accessibilityEnabled"))
        assertFalse(home.contains("Manual pairing (fallback)"))
    }

    @Test fun settingsUsesHostReaderNotDisabledBaseService() {
        val reader = source("adb/PairingAccessibilityService.kt")
        assertTrue(reader.contains("PairingAccessibilityService::class.java"))
        assertTrue(source("adb/AdbCoordinator.kt")
            .contains("PairingAccessibilityService.onPairingPortDiscovered()"))
    }
}
