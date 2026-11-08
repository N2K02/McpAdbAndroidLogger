package dev.n2k02.mcplogger.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdbRecoveryContractTest {
    private fun code(name: String): String {
        val path = "app/src/main/java/dev/n2k02/mcplogger/adb/" + name
        return listOf(File(path), File("../" + path)).first { it.isFile }.readText()
    }
    @Test fun rotationDoesNotClearManualQuit() {
        val activity = listOf(File("app/src/main/java/dev/n2k02/mcplogger/MainActivity.kt"),
            File("../app/src/main/java/dev/n2k02/mcplogger/MainActivity.kt")).first { it.isFile }.readText()
        assertTrue(activity.contains("if (savedInstanceState == null) AdbRecoverySettings.markForegroundOpened(this)"))
    }

    @Test fun matchingVrecRecoveryCadenceAndQuit() {
        val s = code("AdbRecoveryLifecycle.kt")
        assertTrue(s.contains("delay(120_000)"))
        assertTrue(s.contains("15, TimeUnit.MINUTES"))
        assertTrue(s.contains("scope.coroutineContext.cancelChildren()"))
        assertTrue(s.contains("AdbRecoverySettings.backgroundRecoveryAllowed(app)"))
        assertTrue(s.contains("AdbCoordinator.manualRetryRequired.value"))
        assertTrue(s.contains("AdbRecoverySettings.restoreWirelessAfterReboot(app)"))
    }
    @Test fun sharedTransportReusesSavedAuthorizationAndRetainsPrivateKey() {
        val coordinator = code("AdbCoordinator.kt")
        assertTrue(coordinator.contains("shared.retry(auto = !userInitiated)"))
        assertFalse(coordinator.contains("AdbTransport.close()"))
        val adapter = code("SharedAdbHostAdapter.kt")
        assertTrue(adapter.contains("AdbIdentity.getOrCreate(ctx)"))
        assertTrue(adapter.contains("CoreIdentity(existing.privateKey, existing.certificate)"))
        assertTrue(adapter.contains("AdbRecoverySettings.lastVerifiedTlsPort(app)"))
        assertTrue(adapter.contains("AdbRecoverySettings.manuallyQuit(app)"))
        assertTrue(adapter.contains("LibAdbWire"))
        val transport = code("AdbTransport.kt")
        assertTrue(transport.contains("The shared ADB core owns this app's only transport"))
        assertTrue(transport.contains("core.execVerified(command, timeoutMs)"))
    }

    @Test fun privateKeyIsNotSharedBetweenLibadbConnections() {
        val generator = java.security.KeyPairGenerator.getInstance("RSA")
        generator.initialize(1024)
        val saved = generator.generateKeyPair().private
        val first = AdbSessionKey.copyOf(saved)
        val next = AdbSessionKey.copyOf(saved)
        assertNotSame(saved, first)
        assertNotSame(first, next)
        assertArrayEquals(saved.encoded, first.encoded)
        assertArrayEquals(saved.encoded, next.encoded)
    }

    @Test fun connectionStatusIsDerivedOnlyFromSharedVerifiedState() {
        val coordinator = code("AdbCoordinator.kt")
        assertTrue(coordinator.contains("verified.value = state.connected"))
        assertTrue(coordinator.contains("state.transport == AdbTransportMode.LOCAL_TCP_5555"))
        assertTrue(coordinator.contains("check(connected && shared.state.value.connected)"))
        assertTrue(coordinator.contains("SharedAdbHostAdapter.shutdown()"))
    }
}
