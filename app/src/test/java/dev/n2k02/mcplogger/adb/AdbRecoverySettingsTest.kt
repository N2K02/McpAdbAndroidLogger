package dev.n2k02.mcplogger.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AdbRecoverySettingsTest {
    private fun decide(opt: Boolean = true, permission: Boolean = true,
        paired: Boolean = true, wifi: Boolean = true, quit: Boolean = false,
        sdk: Int = 35, on: Boolean = false, boot: Int = 11, lastBoot: Int = 10) =
        AdbRecoverySettings.decideRestore(opt, permission, paired, wifi, quit,
            sdk, on, boot, lastBoot)

    @Test fun rebootRestoreOptInIsExplicitAndGuarded() {
        assertEquals(AdbRecoverySettings.RestoreDecision.NOT_OPTED_IN, decide(opt = false))
        assertEquals(AdbRecoverySettings.RestoreDecision.NO_PERMISSION, decide(permission = false))
        assertEquals(AdbRecoverySettings.RestoreDecision.NOT_PAIRED, decide(paired = false))
        assertEquals(AdbRecoverySettings.RestoreDecision.MANUAL_QUIT, decide(quit = true))
        assertEquals(AdbRecoverySettings.RestoreDecision.UNSUPPORTED, decide(sdk = 29))
        assertEquals(AdbRecoverySettings.RestoreDecision.NO_WIFI, decide(wifi = false))
        assertEquals(AdbRecoverySettings.RestoreDecision.ALREADY_ON, decide(on = true))
        assertEquals(AdbRecoverySettings.RestoreDecision.USER_TURNED_OFF, decide(boot = 10))
        assertEquals(AdbRecoverySettings.RestoreDecision.USER_TURNED_OFF, decide(boot = -1))
        assertEquals(AdbRecoverySettings.RestoreDecision.USER_TURNED_OFF, decide(lastBoot = -1))
        assertEquals(AdbRecoverySettings.RestoreDecision.RESTORE, decide())
    }

    @Test fun tlsPortReusableOnlyWithinSameBoot() {
        assertEquals(37123, AdbRecoverySettings.reusablePort(37123, 12, 12))
        assertNull(AdbRecoverySettings.reusablePort(37123, 12, 13))
        assertNull(AdbRecoverySettings.reusablePort(37123, -1, -1))
        assertNull(AdbRecoverySettings.reusablePort(70000, 12, 12))
    }

    @Test fun authorizationFailuresNeedUserRetryButNetworkFailuresCanRecover() {
        assertTrue(AdbRecoverySettings.requiresManualRetry(
            SecurityException("denied")))
        assertFalse(AdbRecoverySettings.requiresManualRetry(IOException("socket lost")))
        assertFalse(AdbRecoverySettings.requiresManualRetry(
            IllegalStateException("Android TLS debugging connection not authorized")))
        assertFalse(AdbRecoverySettings.requiresManualRetry(
            IllegalStateException("No local ADB port")))
    }
}
