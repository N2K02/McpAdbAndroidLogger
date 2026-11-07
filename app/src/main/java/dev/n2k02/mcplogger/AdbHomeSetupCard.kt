package dev.n2k02.mcplogger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.n2k02.adb.core.AdbHomeSetupPage
import dev.n2k02.adb.core.AdbPageLifecycle
import dev.n2k02.mcplogger.adb.SharedAdbHostAdapter

/** One canonical pairing UI for all N2K02 apps.
 * MCP's own Accessibility service remains registered for automatic code entry. */
@Composable
internal fun AdbHomeSetupCard() {
    val context = LocalContext.current
    val adb = remember(context.applicationContext) { SharedAdbHostAdapter.core(context) }
    // Home is a stable Column child, never an item in a LazyColumn.
    AdbPageLifecycle(adb)
    val state by adb.state.collectAsState()
    if (state.connected) return
    AdbHomeSetupPage(adb, accessibilityEnabled =
        dev.n2k02.mcplogger.adb.PairingAccessibilityService.enabled(context))
}
