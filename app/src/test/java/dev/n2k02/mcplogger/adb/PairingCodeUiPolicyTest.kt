package dev.n2k02.mcplogger.adb

import org.junit.Assert.*
import org.junit.Test

class PairingCodeUiPolicyTest {
    @Test fun settingsDialogOnly() {
        assertFalse(PairingCodeUiPolicy.isSettingsPackage("com.whatsapp"))
        assertTrue(PairingCodeUiPolicy.isSettingsPackage("com.android.settings"))
        assertNull(PairingCodeUiPolicy.extractCode(listOf("123456")))
        assertEquals("123456", PairingCodeUiPolicy.extractCode(
            listOf("Pair device with pairing code", "123456")))
    }
    @Test fun rejectsNonPairingNumbers() {
        assertNull(PairingCodeUiPolicy.extractCode(listOf("Wi-Fi settings", "123456")))
        assertEquals("654321", PairingCodeUiPolicy.extractCode(
            listOf("Wi-Fi pairing code: 654321")))
    }
}
