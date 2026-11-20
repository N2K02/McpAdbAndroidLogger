package dev.n2k02.mcplogger

import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import rikka.shizuku.Shizuku

/** Identical Shizuku connection screen in N2K02 apps; no cached grant is trusted. */
@Composable
fun ShizukuSettingsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val sharedStatus = rememberShizukuAccessStatus()
    // The same live state drives Home and Settings; manual refresh can force
    // a fresh read without creating a second independent Binder listener.
    val state = remember(sharedStatus, revision) { readShizukuAccessStatus() }
    val connected = state.serverOnline
    val allowed = state.permissionGranted
    val ready = state.shellReady
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = if (ready)
                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.SettingsInputAntenna, null, modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("ADB connection", style = MaterialTheme.typography.titleLarge)
                        Text("Secure shell service", style = MaterialTheme.typography.bodySmall)
                    }
                }
                StatusBadge(if (ready) "CONNECTED · SHELL READY" else "ACTION REQUIRED", ready)
                Text(if (ready) "Authorized to use the running ADB service."
                    else "Connect to LocalADB and approve this application's ADB access.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        Card(shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Connection diagnostics", style = MaterialTheme.typography.titleMedium)
                DiagnosticRow("Server", if (connected) "ONLINE" else "OFFLINE", connected)
                HorizontalDivider()
                DiagnosticRow("Permission", if (allowed) "GRANTED" else "REQUIRED", allowed)
                HorizontalDivider()
                DiagnosticRow("Shell identity", if (ready) "UID 2000" else "NOT VERIFIED", ready)
            }
        }
        Card(shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Manage access", style = MaterialTheme.typography.titleMedium)
                if (!connected) {
                    Button(onClick = {
                        val launch = context.packageManager.getLaunchIntentForPackage("dev.n2k02.localadb")
                        if (launch != null) {
                            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/N2K02/LocalAdb/releases"))
                            )
                        }
                        revision++
                    }, modifier = Modifier.fillMaxWidth()) { Text("Connect via LocalADB") }
                } else if (!allowed) {
                    Button(onClick = {
                        runCatching { Shizuku.requestPermission(2902) }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Grant ADB access") }
                }
                if (!allowed) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/N2K02/LocalAdb/releases")))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Get LocalADB · GitHub Releases") }
                }
                OutlinedButton(onClick = { revision++ }, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh connection status")
                }
                Text("Access is granted separately to each app. Status does not guarantee a privileged feature is running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, badge: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        StatusBadge(badge, good)
    }
}

@Composable
private fun StatusBadge(label: String, good: Boolean) {
    Surface(shape = RoundedCornerShape(50),
        color = if (good) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.secondaryContainer) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}
