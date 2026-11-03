package dev.n2k02.mcplogger

import android.os.Bundle
import android.content.Intent
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
