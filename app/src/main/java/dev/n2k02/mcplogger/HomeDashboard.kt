package dev.n2k02.mcplogger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.n2k02.mcplogger.core.BridgeClient
import dev.n2k02.mcplogger.core.MonitorState
import dev.n2k02.mcplogger.core.LogMonitorService
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
    val tunnel by MonitorState.tunnel.collectAsState()
    val publicUrl by MonitorState.publicMcpUrl.collectAsState()
    val stats by MonitorState.captureStats.collectAsState()
    val buffered by MonitorState.logs.collectAsState()
    val verified = adb.startsWith("UID 2000 verified")
    val context = LocalContext.current
    val clientToken = BridgeClient.clientTokenState(context).collectAsState().value.orEmpty()
    var copyHint by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Device diagnostics", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold)
        Text("Live status and Logcat collection from this Android device",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        ElevatedCard(onClick = onAdb, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardHeader(Icons.Rounded.SettingsInputAntenna, "ADB shell", adbTone(adb),
                    if (verified) "UID 2000" else "Needs setup",
                    subtitle = if (verified) "Connected · shell verified" else "ADB authorization needed")
                Text(adb, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Connection details", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CardHeader(Icons.Rounded.Terminal, "Android Logcat",
                    if (active) Tone.GOOD else Tone.IDLE, if (active) "Running" else "Paused")
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

        // Read-only MCP connection details; changes happen on the MCP connection page.
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CardHeader(Icons.Rounded.Cloud, "Public MCP", tunnelTone(tunnel),
                    when (tunnelTone(tunnel)) {
                        Tone.GOOD -> "Online"; Tone.BUSY -> "Connecting"
                        Tone.BAD -> "Offline"; Tone.IDLE -> "Stopped"
                    }, subtitle = tunnel) {
                    IconButton(onClick = onMcp) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "MCP connection")
                    }
                }
                ReadOnlyCopyField("Public HTTPS MCP URL", publicUrl.ifBlank { "Not available yet" },
                    copyEnabled = publicUrl.isNotBlank(), copyDescription = "Copy public MCP URL") {
                    copyHint = copyText(context, "Public MCP URL", publicUrl, sensitive = false)
                }
                ReadOnlyCopyField("MCP client access key",
                    if (clientToken.isBlank()) "Not set" else "•".repeat(24),
                    copyEnabled = clientToken.isNotBlank(),
                    copyDescription = "Copy MCP client access key") {
                    copyHint = copyText(context, "MCP client access key", clientToken, sensitive = true)
                }
                if (copyHint.isNotEmpty()) Text(copyHint, style = MaterialTheme.typography.bodySmall)
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CardHeader(Icons.Rounded.Insights, "Logcat activity",
                    if (stats.readFailures > 0) Tone.BAD else Tone.BUSY,
                    if (stats.readFailures > 0) "${stats.readFailures} errors" else null,
                    subtitle = "This session")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatMetric("Lines scanned", stats.linesScanned.toString(), Modifier.weight(1f), Tone.IDLE)
                    StatMetric("Unique captured", stats.uniqueCaptured.toString(), Modifier.weight(1f), Tone.GOOD)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatMetric("Buffered", buffered.size.toString(), Modifier.weight(1f), Tone.BUSY)
                    StatMetric("Scan cycles", stats.scanCycles.toString(), Modifier.weight(1f), Tone.IDLE)
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
                OutlinedButton(
                    onClick = {
                        LogMonitorService.resetHomeStatistics()
                        copyHint = ""
                    },
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.RestartAlt, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reset Home statistics")
                }
                Text("Resets collection counts and prior read errors. Live ADB and MCP " +
                    "connection status, saved keys, and existing buffered logs stay unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatMetric(label: String, value: String, modifier: Modifier, tone: Tone) {
    val t = tone.colors()
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large,
        color = t.container, contentColor = t.content) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ReadOnlyCopyField(label: String, value: String, copyEnabled: Boolean,
    copyDescription: String, onCopy: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onCopy, enabled = copyEnabled) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = copyDescription)
            }
        }
    }
}

/** Sensitive values are flagged so Android hides them from clipboard previews. */
private fun copyText(context: Context, label: String, value: String, sensitive: Boolean): String {
    if (value.isBlank()) return ""
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, value)
    if (sensitive && Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = (clip.description.extras ?: android.os.PersistableBundle()).apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    return if (sensitive) "$label copied. Clear the clipboard after use." else "$label copied."
}
