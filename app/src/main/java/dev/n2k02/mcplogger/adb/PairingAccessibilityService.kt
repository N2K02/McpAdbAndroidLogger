package dev.n2k02.mcplogger.adb

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.n2k02.mcplogger.core.MonitorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Optional first-pairing convenience. Reads only a visible Android Settings pairing dialog;
 * no code is stored, logged, uploaded, or used to bypass system authorization.
 * Adapted from VRec's PairingCodeUiPolicy and restricted Accessibility flow.
 */
class PairingAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var current: PairingAccessibilityService? = null
        fun onPairingPortDiscovered() { current?.scheduleRead() }
        fun enabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val name = ComponentName(context, PairingAccessibilityService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(name, ignoreCase = true) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var remaining = 0
    private var lastSignature: Int? = null
    private var lastDiscoveryMs = 0L
    private val read = object : Runnable {
        override fun run() {
            if (scan()) return
            if (remaining-- > 0) handler.postDelayed(this, 400L)
        }
    }

    private fun scheduleRead() {
        handler.removeCallbacks(read)
        remaining = 15
        handler.post(read)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        scheduleRead()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !PairingCodeUiPolicy.isSettingsPackage(event.packageName?.toString())) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) scheduleRead()
    }

    private fun scan(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!PairingCodeUiPolicy.isSettingsPackage(root.packageName?.toString())) return false
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        val labels = ArrayList<String>()
        nodes.add(root)
        var examined = 0
        while (nodes.isNotEmpty() && examined++ < 100) {
            val node = nodes.removeFirst()
            node.text?.toString()?.let(labels::add)
            node.contentDescription?.toString()?.let(labels::add)
            for (i in 0 until node.childCount.coerceAtMost(20)) {
                node.getChild(i)?.let(nodes::add)
            }
        }
        val code = PairingCodeUiPolicy.extractCode(labels) ?: return false
        val port = AdbCoordinator.pairingPort
        if (port == null) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDiscoveryMs > 10_000L) {
                lastDiscoveryMs = now
                AdbCoordinator.discover(applicationContext)
            }
            return false
        }
        val signature = (port.toString() + ":" + root.windowId + ":" + code).hashCode()
        if (signature == lastSignature) return true
        lastSignature = signature
        scope.launch {
            AdbCoordinator.pair(applicationContext, port, code).onSuccess {
                AdbCoordinator.connect(applicationContext)
            }.onFailure {
                MonitorState.error("Auto pairing failed; manual pairing remains available")
            }
        }
        return true
    }

    override fun onInterrupt() = Unit
    override fun onDestroy() {
        handler.removeCallbacks(read)
        if (current === this) current = null
        scope.cancel()
        super.onDestroy()
    }
}
