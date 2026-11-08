package dev.n2k02.mcplogger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.n2k02.mcplogger.core.BridgeClient
import dev.n2k02.mcplogger.core.MonitorState
import dev.n2k02.mcplogger.core.LogMonitorService
import dev.n2k02.mcplogger.core.LogDayPolicy
import dev.n2k02.mcplogger.core.LogCaptureStats
import dev.n2k02.mcplogger.core.LogReadOrigin
import dev.n2k02.mcplogger.core.PerformanceDiagnostics
import dev.n2k02.mcplogger.core.DeviceDiagnostics
import dev.n2k02.mcplogger.core.LocalMcpProtocol
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.DateFormat
import java.util.Date

/** Home is a single manual-Logcat overview; MCP diagnostics are remote-only. */
@Composable
internal fun HomeDashboard(
    modifier: Modifier = Modifier,
    onLogs: () -> Unit,
    onAdb: () -> Unit,
    onMcp: () -> Unit,
) {
    val active by MonitorState.active.collectAsState()
    val inFlight by MonitorState.mcpToolsInFlight.collectAsState()
    val mcpReadCount by MonitorState.mcpReadCount.collectAsState()
    val mcpLastReadAt by MonitorState.mcpLastReadAt.collectAsState()
    val adb by MonitorState.adb.collectAsState()
    val tunnel by MonitorState.tunnel.collectAsState()
    val stats by MonitorState.captureStats.collectAsState()
    val manualLogs by MonitorState.logs.collectAsState()
    val today = LogDayPolicy.dayStart(System.currentTimeMillis())
    val currentStats = if (stats.dayStartMs == today) stats else LogCaptureStats(dayStartMs = today)
    val buffered = manualLogs.count { it.epochMillis >= today }
    val verified = adb.startsWith("UID 2000 verified")
    val mcpActive = inFlight.isNotEmpty()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardHeader(Icons.Rounded.Terminal, "Manual Logcat (Today)",
                    if (active) Tone.BUSY else Tone.IDLE,
                    if (active) "Reading" else "Ready",
                    subtitle = "Manual Logs page only; MCP reads are separate")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatMetric("Captured today", currentStats.uniqueCaptured.toString(),
                        Modifier.weight(1f), Tone.GOOD)
                    StatMetric("Buffered", buffered.toString(), Modifier.weight(1f), Tone.BUSY)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatMetric("Manual searches", currentStats.scanCycles.toString(),
                        Modifier.weight(1f), Tone.IDLE)
                    StatMetric("Last manual search",
                        if (currentStats.lastSuccessAtMillis == 0L) "—"
                        else DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(Date(currentStats.lastSuccessAtMillis)),
                        Modifier.weight(1f), Tone.IDLE)
                }
                if (currentStats.readFailures > 0) {
                    Text("Manual read errors: " + currentStats.readFailures,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { LogMonitorService.resetHomeStatistics() },
                    modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Rounded.RestartAlt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset Home statistics")
                }
            }
        }
        HomeStatusCard(Icons.Rounded.Terminal, "Manual Logs",
            "Open Logs and tap Search to scan Android Logcat",
            HomeBadgePolicy.logs(active, buffered > 0), onClick = onLogs)
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardHeader(Icons.Rounded.Cloud, "MCP activity",
                    if (mcpActive) Tone.BUSY else Tone.IDLE,
                    if (mcpActive) "Reading" else "Idle",
                    subtitle = "Remote diagnostics do not change manual Logs")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatMetric("MCP Logcat reads", mcpReadCount.toString(),
                        Modifier.weight(1f), Tone.GOOD)
                    StatMetric("Tools active", inFlight.size.toString(),
                        Modifier.weight(1f), if (mcpActive) Tone.BUSY else Tone.IDLE)
                }
                Text("Last MCP Logcat read: " +
                    if (mcpLastReadAt == 0L) "—" else
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(mcpLastReadAt)),
                    style = MaterialTheme.typography.bodySmall)
                Text("ADB: " + if (verified) "UID 2000 verified" else "Authorization needed",
                    style = MaterialTheme.typography.bodySmall)
                Text("MCP: " + tunnel, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2)
            }
        }
        HomeStatusCard(Icons.Rounded.SettingsInputAntenna, "ADB connection",
            if (verified) "Connected · UID 2000" else "Setup required",
            HomeBadgePolicy.adb(verified, false), onClick = onAdb)
        HomeStatusCard(Icons.Rounded.Cloud, "MCP connection", tunnel,
            HomeBadgePolicy.mcp(tunnel, mcpActive), onClick = onMcp)
    }
}

@Composable
private fun StatMetric(label: String, value: String, modifier: Modifier, tone: Tone) {
    val t = tone.colors()
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large,
        color = t.container, contentColor = t.content) {
        Column(Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
            Text(label, style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Compact clickable navigation row with a state-derived status pill. */
@Composable
private fun HomeStatusCard(icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String, subtitle: String, badge: HomeBadge,
    sourceBadge: HomeBadge? = null, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(5.dp))
            if (sourceBadge != null) {
                StatusBadge(sourceBadge.label, sourceBadge.tone)
                Spacer(Modifier.width(4.dp))
            }
            StatusBadge(badge.label, badge.tone)
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Open " + title,
                modifier = Modifier.size(16.dp))
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
