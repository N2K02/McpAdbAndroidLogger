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
        .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // Connection health is visible immediately; no duplicated status rows.
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Connection status", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeConnectionTile("ADB",
                    if (verified) "Shell UID 2000" else "Setup required",
                    Icons.Rounded.SettingsInputAntenna,
                    HomeBadgePolicy.adb(verified, false), Modifier.weight(1f), onAdb)
                HomeConnectionTile("MCP",
                    when (tunnelTone(tunnel)) {
                        Tone.GOOD -> "Public endpoint"
                        Tone.BUSY -> "Connecting…"
                        else -> "Check connection"
                    },
                    Icons.Rounded.Cloud,
                    HomeBadgePolicy.mcp(tunnel, mcpActive), Modifier.weight(1f), onMcp)
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CardHeader(Icons.Rounded.Terminal, "Manual Logcat (Today)",
                    if (active) Tone.BUSY else Tone.IDLE,
                    if (active) "Reading" else if (verified) "Ready" else "No ADB",
                    subtitle = "Local searches only")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeInlineMetric("Captured today", currentStats.uniqueCaptured.toString(),
                        Modifier.weight(1f), emphasized = true)
                    VerticalDivider(Modifier.height(44.dp))
                    HomeInlineMetric("Buffered", buffered.toString(),
                        Modifier.weight(1f).padding(start = 16.dp))
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Manual searches: " + currentStats.scanCycles,
                            style = MaterialTheme.typography.bodySmall)
                        Text("Last manual search: " +
                            if (currentStats.lastSuccessAtMillis == 0L) "—"
                            else DateFormat.getTimeInstance(DateFormat.SHORT)
                                .format(Date(currentStats.lastSuccessAtMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onLogs, enabled = !active,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                        Icon(Icons.Rounded.Terminal, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Manual Logs", maxLines = 1)
                    }
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

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CardHeader(Icons.Rounded.Cloud, "MCP activity",
                    if (mcpActive) Tone.BUSY else Tone.IDLE,
                    if (mcpActive) "Reading" else "Idle",
                    subtitle = "On-demand diagnostics · separate from manual Logs")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeInlineMetric("MCP Logcat reads", mcpReadCount.toString(),
                        Modifier.weight(1f), emphasized = true)
                    VerticalDivider(Modifier.height(44.dp))
                    HomeInlineMetric("Tools active", inFlight.size.toString(),
                        Modifier.weight(1f).padding(start = 16.dp))
                }
                Text("Last MCP Logcat read: " +
                    if (mcpLastReadAt == 0L) "—"
                    else DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(Date(mcpLastReadAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { MonitorState.resetMcpHomeStatistics() },
                    modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Rounded.RestartAlt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset MCP statistics")
                }
            }
        }
    }
}

@Composable
private fun HomeInlineMetric(label: String, value: String, modifier: Modifier,
    emphasized: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Two compact, separately navigable connection cards instead of repeated rows. */
@Composable
private fun HomeConnectionTile(title: String, subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector, badge: HomeBadge,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(7.dp))
                Text(title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight,
                    contentDescription = "Open " + title + " connection",
                    modifier = Modifier.size(16.dp))
            }
            StatusBadge(badge.label, badge.tone)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
