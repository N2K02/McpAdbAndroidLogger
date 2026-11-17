package dev.n2k02.mcplogger

import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import rikka.shizuku.Shizuku

/** Single, live source of truth for the Home ADB tile and Settings → ADB. */
internal data class ShizukuAccessStatus(
    val serverOnline: Boolean,
    val permissionGranted: Boolean,
    val shellUid: Int?,
) {
    val shellReady: Boolean get() =
        serverOnline && permissionGranted && shellUid == Process.SHELL_UID
}

internal fun readShizukuAccessStatus(): ShizukuAccessStatus {
    val online = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    val granted = online && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    val uid = if (granted) runCatching { Shizuku.getUid() }.getOrNull() else null
    return ShizukuAccessStatus(online, granted, uid)
}

/**
 * Observe binder arrivals/deaths, permission changes and foreground returns.
 * Never derive Home's badge from a separate service session or cached message.
 */
@Composable
internal fun rememberShizukuAccessStatus(): ShizukuAccessStatus {
    val lifecycleOwner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        val received = Shizuku.OnBinderReceivedListener { revision++ }
        val died = Shizuku.OnBinderDeadListener { revision++ }
        val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> revision++ }
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(died)
        Shizuku.addRequestPermissionResultListener(permission)
        onDispose {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(died)
            Shizuku.removeRequestPermissionResultListener(permission)
        }
    }
    return remember(revision) { readShizukuAccessStatus() }
}
