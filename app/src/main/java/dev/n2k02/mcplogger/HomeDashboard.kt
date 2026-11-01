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
import dev.n2k02.mcplogger.core.LogDayPolicy
import dev.n2k02.mcplogger.core.LogViewPolicy
import dev.n2k02.mcplogger.core.LogCaptureStats
import dev.n2k02.mcplogger.core.PerformanceDiagnostics
import kotlinx.coroutines.launch
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
    val logFilter by MonitorState.logFilter.collectAsState()
    val packageUids by MonitorState.packageUids.collectAsState()
    val today = LogDayPolicy.dayStart(System.currentTimeMillis())
    val currentStats = if (stats.dayStartMs == today) stats else LogCaptureStats(dayStartMs = today)
    val todayBuffered = remember(buffered, today) {
        buffered.count { it.epochMillis >= today }
    }
    val todayShown = remember(buffered, logFilter, packageUids, today) {
        LogViewPolicy.select(buffered, logFilter, packageUids, System.currentTimeMillis())
            .count { it.epochMillis >= today }
    }
    val verified = adb.startsWith("UID 2000 verified")
    val context = LocalContext.current
    val clientToken = BridgeClient.clientTokenState(context).collectAsState().value.orEmpty()
    var copyHint by remember { mutableStateOf("") }
    val perfScope = rememberCoroutineScope()
    var perfPackage by remember { mutableStateOf("") }
    var perfBusy by remember { mutableStateOf(false) }
    var perfResult by remember { mutableStateOf("No performance snapshot yet.") }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Device diagnostics", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold)
        Text("ADB diagnostics and on-demand Android Logcat snapshots",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CardHeader(Icons.Rounded.Terminal, "Logcat (Today)", Tone.IDLE,
                    "Daily reset enabled",
                    subtitle = "Today's local-day capture summary")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatMetric("Captured today", currentStats.uniqueCaptured.toString(),
                        Modifier.weight(1f), Tone.GOOD)
                    StatMetric("Shown today", todayShown.toString(),
                        Modifier.weight(1f), Tone.BUSY)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatMetric("Read cycles", currentStats.scanCycles.toString(),
                        Modifier.weight(1f), Tone.IDLE)
                    StatMetric("Buffered today", todayBuffered.toString(),
                        Modifier.weight(1f), Tone.BUSY)
                }
                Text(if (currentStats.lastSuccessAtMillis == 0L) "No refresh today"
                    else "Last refresh today: " +
                        DateFormat.getTimeInstance(DateFormat.MEDIUM)
                            .format(Date(currentStats.lastSuccessAtMillis)),
                    style = MaterialTheme.typography.bodySmall)
                Text("Private cache and counters reset at local midnight. " +
                    "Android's system Logcat and MCP audit history are unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (currentStats.readFailures > 0L)
                    Text("Read errors today: " + currentStats.readFailures,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = {
                    LogMonitorService.resetHomeStatistics()
                    copyHint = ""
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.RestartAlt, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reset Home statistics")
                }
                Text("Resets today's Home counts only, not buffered logs or connection state.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

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
                    if (active) Tone.GOOD else Tone.IDLE, if (active) "Reading" else "Idle")
                Text("No background polling. Refresh here or request logs through MCP; filter results in Logs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onStart, enabled = !active,
                        modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Refresh")
                    }
                    OutlinedButton(onClick = onStop, enabled = active,
                        modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Stop, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel")
                    }
                }
            }
        }


        // CPU and UI frame probes only run when explicitly tapped.
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CardHeader(Icons.Rounded.Speed, "CPU & frame timing", Tone.IDLE,
                    if (perfBusy) "Reading" else "On demand")
                Text("One short CPU sample or recent View UI frames. No background sampling. " +
                    "This does not measure hardware GPU utilization.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = perfPackage, onValueChange = { perfPackage = it.take(150) },
                    label = { Text("Package (optional for CPU)") },
                    placeholder = { Text("com.vrec.app") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        perfBusy = true
                        perfScope.launch {
                            try {
                                val result = PerformanceDiagnostics.cpu(perfPackage.trim(), 10)
                                val processes = result.getJSONArray("processes")
                                perfResult = if (processes.length() == 0)
                                    "No matching running processes in this snapshot."
                                else (0 until minOf(5, processes.length())).joinToString("\n") { i ->
                                    val process = processes.getJSONObject(i)
                                    process.getString("process") + " · " +
                                        process.getDouble("cpu_percent") + "% CPU · " +
                                        process.getString("resident_memory") + " RAM"
                                }
                            } catch (_: Exception) {
                                perfResult = "CPU read unavailable. Check ADB and the package name."
                            } finally {
                                perfBusy = false
                            }
                        }
                    }, enabled = verified && !perfBusy, modifier = Modifier.weight(1f)) {
                        Text("CPU snapshot")
                    }
                    OutlinedButton(onClick = {
                        perfBusy = true
                        perfScope.launch {
                            try {
                                val result = PerformanceDiagnostics.frames(perfPackage.trim())
                                perfResult = if (result.getString("status") != "ok")
                                    "No View frame timing for this app; video/GPU paths may not be exposed."
                                else result.getInt("sample_frames").toString() +
                                    " UI frames · p95 " +
                                    result.getDouble("frame_p95_ms") + " ms · slow " +
                                    result.getInt("slow_frames") + " (60 Hz reference)"
                            } catch (_: Exception) {
                                perfResult = "Frame timing unavailable. Check ADB and package name."
                            } finally {
                                perfBusy = false
                            }
                        }
                    }, enabled = verified && !perfBusy &&
                        PerformanceDiagnostics.validPackage(perfPackage.trim()),
                        modifier = Modifier.weight(1f)) {
                        Text("Frame timing")
                    }
                }
                Text(perfResult, style = MaterialTheme.typography.bodySmall)
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
