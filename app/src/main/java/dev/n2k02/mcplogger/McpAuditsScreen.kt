package dev.n2k02.mcplogger

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.n2k02.mcplogger.core.McpAuditEntry
import dev.n2k02.mcplogger.core.McpAuditPolicy
import dev.n2k02.mcplogger.core.McpAuditStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Settings → MCP audits. Never displays or exports authorization credentials. */
@Composable
internal fun McpAuditsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by McpAuditStore.entries.collectAsState()
    var filter by remember { mutableStateOf(McpAuditPolicy.Filter()) }
    val visible = remember(entries, filter) {
        McpAuditPolicy.select(entries, filter, System.currentTimeMillis()).asReversed()
    }
    var agentMenu by remember { mutableStateOf(false) }
    var toolMenu by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var timeMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf(emptyList<McpAuditEntry>()) }
    val agents = remember(entries) { entries.distinctBy { it.agentId }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.agentName }) }
    val tools = remember(entries) { entries.map { it.tool }.filter { it.isNotBlank() }
        .distinct().sorted() }
    val statuses = listOf("OK", "Error", "Accepted", "Denied")

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { McpAuditStore.reload(context) } }

    fun copy(value: String, name: String) {
        if (value.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(name, value.take(120_000))
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = (clip.description.extras ?: android.os.PersistableBundle()).apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        feedback = "Redacted audit copied"
    }
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val copyOfRows = snapshot
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val stream = context.contentResolver.openOutputStream(uri)
                            ?: error("Destination unavailable")
                        stream.bufferedWriter(Charsets.UTF_8).use {
                            it.write(McpAuditPolicy.export(copyOfRows))
                        }
                        true
                    }.getOrDefault(false)
                }
                feedback = if (ok) "Exported " + copyOfRows.size + " redacted records"
                    else "Could not export audits"
            }
        }
    }

    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Clear MCP audit history?") },
        text = { Text("Delete all locally saved MCP audit records? This does not revoke agents or delete Android Logcat.") },
        confirmButton = {
            TextButton(onClick = {
                McpAuditStore.clear(context)
                confirmClear = false
                feedback = "MCP audits cleared"
            }) { Text("Clear history") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MCP activity by AI agent · Today only",
                style = MaterialTheme.typography.titleMedium)
            Text("Audit journal resets at local midnight; older entries are automatically pruned.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("OAuth agent names are self-registered. Unverified client hints may be spoofed. " +
                "Read-only MCP requests, redacted request parameters, and the response sent. " +
                "Stored only on this phone. Up to 160 records are retained; large payload previews are truncated.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                McpAuditMetric("Total calls", entries.size.toString(), Modifier.weight(1f))
                McpAuditMetric("Matching", visible.size.toString(), Modifier.weight(1f))
                McpAuditMetric("Denied", entries.count { it.status == "Denied" }.toString(),
                    Modifier.weight(1f))
            }
            OutlinedTextField(value = filter.query,
                onValueChange = { filter = filter.copy(query = it.take(150)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Search methods or audit data") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (filter.query.isNotEmpty()) IconButton(onClick = {
                        filter = filter.copy(query = "")
                    }) { Icon(Icons.Rounded.Clear, contentDescription = "Clear search") }
                })
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box {
                    FilterChip(selected = filter.agentId.isNotBlank(), onClick = { agentMenu = true },
                        label = { Text(agents.firstOrNull { it.agentId == filter.agentId }?.agentName
                            ?: "All agents") },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) })
                    DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                        DropdownMenuItem(text = { Text("All agents") }, onClick = {
                            filter = filter.copy(agentId = ""); agentMenu = false
                        })
                        agents.forEach { agent ->
                            DropdownMenuItem(
                                text = { Column {
                                    Text(agent.agentName)
                                    Text(agent.domain, style = MaterialTheme.typography.labelSmall)
                                } },
                                onClick = {
                                    filter = filter.copy(agentId = agent.agentId); agentMenu = false
                                })
                        }
                    }
                }
                Box {
                    FilterChip(selected = filter.tool.isNotBlank(), onClick = { toolMenu = true },
                        label = { Text(filter.tool.ifBlank { "All tools" }) },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) })
                    DropdownMenu(expanded = toolMenu, onDismissRequest = { toolMenu = false }) {
                        DropdownMenuItem(text = { Text("All tools") },
                            onClick = { filter = filter.copy(tool = ""); toolMenu = false })
                        tools.forEach { tool ->
                            DropdownMenuItem(text = { Text(tool) }, onClick = {
                                filter = filter.copy(tool = tool); toolMenu = false
                            })
                        }
                    }
                }
                Box {
                    FilterChip(selected = filter.status.isNotBlank(), onClick = { statusMenu = true },
                        label = { Text(filter.status.ifBlank { "All results" }) },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) })
                    DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        (listOf("" to "All results") + statuses.map { it to it }).forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                filter = filter.copy(status = key); statusMenu = false
                            })
                        }
                    }
                }
                Box {
                    FilterChip(selected = filter.minutes > 0, onClick = { timeMenu = true },
                        label = { Text(when(filter.minutes) {
                            15 -> "15 min"; 60 -> "1 hour"; 1440 -> "24 hours"; else -> "Any time"
                        }) },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) })
                    DropdownMenu(expanded = timeMenu, onDismissRequest = { timeMenu = false }) {
                        listOf(0 to "Any time", 15 to "Last 15 minutes", 60 to "Last hour",
                            1440 to "Last 24 hours").forEach { (minutes, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                filter = filter.copy(minutes = minutes); timeMenu = false
                            })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = { filter = McpAuditPolicy.Filter() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 7.dp)) {
                    Icon(Icons.Rounded.RestartAlt, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp)); Text("Reset")
                }
                OutlinedButton(onClick = { confirmClear = true },
                    enabled = entries.isNotEmpty(), modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 7.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp)); Text("Clear")
                }
                Button(onClick = {
                    snapshot = visible.toList()
                    export.launch("MCP-audits-" + System.currentTimeMillis() + ".txt")
                }, enabled = visible.isNotEmpty(), modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 7.dp)) {
                    Icon(Icons.Rounded.SaveAlt, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp)); Text("Export")
                }
            }
            if (feedback.isNotEmpty()) Text(feedback, style = MaterialTheme.typography.labelSmall)
            HorizontalDivider()
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Text(if (entries.isEmpty()) "No MCP requests recorded yet.\n" +
                    "Connect an AI agent and call an MCP tool to see it here."
                else "No MCP audits match these filters.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(visible, key = { it.id }) { audit ->
                    McpAuditRow(audit) { copy(it, "MCP audit") }
                }
            }
        }
    }
}

@Composable
private fun McpAuditMetric(title: String, number: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Column(Modifier.padding(11.dp)) {
            Text(number, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge)
            Text(title, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Formatting is display-only; stored redacted payloads and exports stay unchanged. */
@Composable
private fun AuditJsonPreview(raw: String) {
    val pretty = remember(raw) { AuditJsonFormatter.pretty(raw) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Box(Modifier.heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)) {
                Text(pretty, style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace, softWrap = false)
            }
        }
    }
}

@Composable
private fun McpAuditRow(entry: McpAuditEntry, onCopy: (String) -> Unit) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    OutlinedCard(onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(shape = RoundedCornerShape(50),
                        color = if (entry.agentId.startsWith("unauthenticated"))
                            MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Rounded.SmartToy, contentDescription = null,
                                modifier = Modifier.size(15.dp))
                            Text(entry.agentName.ifBlank { "Unknown agent" },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(entry.domain, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(entry.status, style = MaterialTheme.typography.labelMedium,
                    color = if (entry.status == "OK" || entry.status == "Accepted")
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Text((if (entry.tool.isNotBlank()) entry.tool else entry.method) +
                (if (entry.httpMethod.isNotBlank()) " · " + entry.httpMethod +
                    " " + entry.endpoint else ""),
                style = MaterialTheme.typography.bodyMedium)
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(Date(entry.timestamp)) + " · " + entry.durationMs + " ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                HorizontalDivider()
                Text("Agent: " + entry.agentName + " · " + entry.domain,
                    style = MaterialTheme.typography.bodySmall)
                Text("MCP method: " + entry.method +
                    (if (entry.tool.isNotBlank()) " · tool: " + entry.tool else ""),
                    style = MaterialTheme.typography.bodySmall)
                if (entry.httpMethod.isNotBlank()) Text(
                    "HTTP: " + entry.httpMethod + " " + entry.endpoint +
                        " · status " + entry.httpStatus,
                    style = MaterialTheme.typography.bodySmall)
                if (entry.reason.isNotBlank()) Text("Reason: " + entry.reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Text("Request · redacted" +
                    if (entry.requestTruncated) " · truncated" else "",
                    style = MaterialTheme.typography.labelMedium)
                AuditJsonPreview(entry.request)
                Text("Response sent · redacted" +
                    if (entry.responseTruncated) " · truncated" else "",
                    style = MaterialTheme.typography.labelMedium)
                AuditJsonPreview(entry.response.ifBlank { "(empty response)" })
                TextButton(onClick = {
                    onCopy(McpAuditPolicy.export(listOf(entry)))
                }) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Copy details")
                }
            } else {
                Text("Tap to inspect request and response", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
