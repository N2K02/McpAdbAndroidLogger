package dev.n2k02.mcplogger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.n2k02.mcplogger.core.MonitorState
import java.text.DateFormat
import java.util.Date

/** Operational dashboard: no log items, app picker or Logcat navigation shortcuts. */
@Composable
internal fun HomeDashboard(
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAdb: () -> Unit,
    onMcp: () -> Unit,
) {
    val active by MonitorState.active.collectAsState()
    val adb by MonitorState.adb.collectAsState()
    val local by MonitorState.localMcp.collectAsState()
    val tunnel by MonitorState.tunnel.collectAsState()
    val publicUrl by MonitorState.publicMcpUrl.collectAsState()
    val stats by MonitorState.captureStats.collectAsState()
    val buffered by MonitorState.logs.collectAsState()
    val verified = adb.startsWith("UID 2000 verified")
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Device diagnostics", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold)
        Text("Live status and Logcat collection from this Android device",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        ElevatedCard(onClick = onAdb, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SettingsInputAntenna, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("ADB shell", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    Icon(if (verified) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = if (verified) "Verified" else "Unavailable",
                        tint = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Text(if (verified) "Connected · shell UID 2000 verified" else "ADB authorization needed",
                    style = MaterialTheme.typography.bodyMedium)
                Text(adb, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Connection details", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Android Logcat", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (active) "● Running" else "● Paused",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Capture all apps; narrow results with the filters in Logs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onStart, enabled = !active,
                        modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Start")
                    }
                    OutlinedButton(onClick = onStop, enabled = active,
                        modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Stop, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Pause")
                    }
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Logcat activity · this session",
                    style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatMetric("Lines scanned", stats.linesScanned.toString(), Modifier.weight(1f))
                    StatMetric("Unique captured", stats.uniqueCaptured.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatMetric("Buffered", buffered.size.toString(), Modifier.weight(1f))
                    StatMetric("Scan cycles", stats.scanCycles.toString(), Modifier.weight(1f))
                }
                Text(if (stats.lastSuccessAtMillis == 0L) "No successful Logcat scan yet"
                    else "Last scan: " + DateFormat.getTimeInstance(DateFormat.MEDIUM)
                        .format(Date(stats.lastSuccessAtMillis)) +
                        " · " + stats.lastBatchCaptured + " new events",
                    style = MaterialTheme.typography.bodySmall)
                if (stats.readFailures > 0) {
                    Text("Read errors: " + stats.readFailures,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Text("Scanned lines may repeat across polls; unique captured counts new events.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        ElevatedCard(onClick = onMcp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("MCP access", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Rounded.ChevronRight, null)
                }
                Text(local, style = MaterialTheme.typography.bodyMedium)
                Text("Public tunnel: " + tunnel, style = MaterialTheme.typography.bodySmall)
                if (publicUrl.isNotBlank()) {
                    Text("Public endpoint available",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun StatMetric(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
