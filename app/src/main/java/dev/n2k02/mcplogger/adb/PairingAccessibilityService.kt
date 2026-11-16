package dev.n2k02.mcplogger.adb

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Context

/** No automatic wireless ADB pairing: the Shizuku manager handles client authorization. */
class PairingAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    companion object {
        fun onPairingPortDiscovered() {}
        fun enabled(context: Context): Boolean = false
    }
}
