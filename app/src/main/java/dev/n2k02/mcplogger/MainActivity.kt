package dev.n2k02.mcplogger

import android.os.Bundle
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import dev.n2k02.mcplogger.adb.AdbCoordinator
import dev.n2k02.mcplogger.adb.AdbRecoveryLifecycle
import dev.n2k02.mcplogger.adb.AdbRecoverySettings
import dev.n2k02.mcplogger.core.BridgeClient
import dev.n2k02.mcplogger.core.LogMonitorService
import dev.n2k02.mcplogger.core.MonitorState
import dev.n2k02.theme.N2K02Theme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val prefs = getSharedPreferences("mcp_logger", MODE_PRIVATE)
            val serviceEnabled = prefs.getBoolean("mcp_server_enabled", true) &&
                !prefs.getBoolean("manual_quit", false)
            if (granted && serviceEnabled) {
                runCatching {
                    ContextCompat.startForegroundService(this,
                        Intent(this, LogMonitorService::class.java)
                            .setAction(LogMonitorService.LOCAL_START))
                }.onFailure {
                    MonitorState.error("Notification refresh: " + it.javaClass.simpleName)
                }
            } else if (!granted) {
                Toast.makeText(this,
                    "Allow notifications to show the MCP tunnel icon in the status bar.",
                    Toast.LENGTH_LONG).show()
            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LiveUpdater.init(this)
        BridgeClient.initialize(this)
        AdbRecoverySettings.markForegroundOpened(this)
        AdbRecoveryLifecycle.start(this)
        // User-launched foreground activity may start the foreground service.
        // Local MCP stays available until Quit.
        if (!getSharedPreferences("mcp_logger", MODE_PRIVATE).getBoolean("manual_quit", false) &&
            getSharedPreferences("mcp_logger", MODE_PRIVATE).getBoolean("mcp_server_enabled", true)) {
            runCatching {
                ContextCompat.startForegroundService(this,
                    Intent(this, LogMonitorService::class.java)
                        .setAction(LogMonitorService.LOCAL_START))
            }.onFailure { MonitorState.error("Local MCP startup: " + it.javaClass.simpleName) }
        }
        lifecycleScope.launch {
            if (!getSharedPreferences("mcp_logger", MODE_PRIVATE).getBoolean("manual_quit", false))
                AdbCoordinator.connect(this@MainActivity)
        }
        setContent {
            N2K02Theme { LoggerApp(
                onQuit = {
                    startService(Intent(this, LogMonitorService::class.java)
                        .setAction(LogMonitorService.QUIT))
                    // Only the foreground Service owns transport teardown and
                    // awaits completion before stopping. Activity work can be
                    // cancelled on screen exit.
                    AdbRecoverySettings.setManuallyQuit(this, true)
                    AdbRecoveryLifecycle.shutdown(this)
                    MonitorState.quit()
                }
            ) }
        }
        requestNotificationPermissionIfNeeded()
    }
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("mcp_logger", MODE_PRIVATE)
        if (prefs.getBoolean("notification_permission_requested", false)) return
        prefs.edit().putBoolean("notification_permission_requested", true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        LiveUpdater.onResume(this)
    }
    override fun onDestroy() {
        // Never stop ADB recovery/discovery or MCP tunnel when the UI is destroyed;
        // their lifecycle belongs to the foreground service and explicit Quit.
        super.onDestroy()
    }
}
