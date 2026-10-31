package dev.n2k02.mcplogger

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import dev.n2k02.mcplogger.adb.AdbCoordinator
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
        AdbCoordinator.discover(this)
        lifecycleScope.launch {
            if (!getSharedPreferences("mcp_logger", MODE_PRIVATE).getBoolean("manual_quit", false))
                AdbCoordinator.connect(this@MainActivity)
        }
        setContent {
            N2K02Theme { LoggerApp(
                onStart = {
                    ContextCompat.startForegroundService(this,
                        Intent(this, LogMonitorService::class.java).setAction(LogMonitorService.START))
                },
                onStop = {
                    startService(Intent(this, LogMonitorService::class.java)
                        .setAction(LogMonitorService.STOP))
                },
                onQuit = {
                    startService(Intent(this, LogMonitorService::class.java)
                        .setAction(LogMonitorService.QUIT))
                    lifecycleScope.launch { AdbCoordinator.quit() }
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
        AdbCoordinator.stopDiscovery()
        super.onDestroy()
    }
}
