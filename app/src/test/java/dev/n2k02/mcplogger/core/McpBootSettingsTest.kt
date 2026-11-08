package dev.n2k02.mcplogger.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpBootSettingsTest {
    @Test fun bootReceiverIgnoresNullAndUnrelatedBroadcasts() {
        assertFalse(BootActionPolicy.accepts(null))
        assertFalse(BootActionPolicy.accepts("android.intent.action.USER_PRESENT"))
        assertFalse(BootActionPolicy.accepts("android.intent.action.PACKAGE_ADDED"))
        assertTrue(BootActionPolicy.accepts("android.intent.action.BOOT_COMPLETED"))
        assertTrue(BootActionPolicy.accepts("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    @Test fun startOnBootDefaultsToEnabled() {
        assertTrue(McpBootSettings.DEFAULT_START_ON_BOOT)
    }

    @Test fun bootStartsMcpOnlyWhenEnabledAndServerWasNotStopped() {
        assertTrue(McpBootSettings.shouldStartOnBoot(
            enabled = true, serverEnabled = true, manuallyQuit = false))
        assertFalse(McpBootSettings.shouldStartOnBoot(
            enabled = false, serverEnabled = true, manuallyQuit = false))
        assertFalse(McpBootSettings.shouldStartOnBoot(
            enabled = true, serverEnabled = false, manuallyQuit = false))
        assertFalse(McpBootSettings.shouldStartOnBoot(
            enabled = true, serverEnabled = true, manuallyQuit = true))
    }
}
