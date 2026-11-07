package dev.n2k02.mcplogger.adb

import android.content.Context
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.SharedAdbPairingAccessibilityService

/**
 * MCP-specific manifest identity only; system pairing UI, code parsing, mDNS
 * discovery, retries and callback cancellation all live in n2k02AdbCoreLibrary.
 */
class PairingAccessibilityService : SharedAdbPairingAccessibilityService() {
    companion object {
        fun onPairingPortDiscovered() =
            SharedAdbPairingAccessibilityService.notifyPairingPortDiscovered()

        fun enabled(context: Context): Boolean =
            SharedAdbPairingAccessibilityService.isEnabled(
                context, PairingAccessibilityService::class.java)
    }

    override fun acquireCore(): AdbCore =
        SharedAdbHostAdapter.core(applicationContext)
}
