package dev.n2k02.mcplogger

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.n2k02.mcplogger.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class InstalledApp(val packageName: String, val label: String)

/** Real installed-app labels; launcher visibility is declared in the manifest. */
private fun resolveApps(context: Context, names: List<String>): List<InstalledApp> =
    names.map { pkg ->
        val title = runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull().orEmpty().ifBlank { pkg }
        InstalledApp(pkg, title)
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    if (packageName.isBlank()) {
        Icon(Icons.Rounded.Apps, contentDescription = null, modifier = modifier.size(32.dp))
        return
    }
    val context = LocalContext.current
    val drawable = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }
            .getOrElse { context.packageManager.defaultActivityIcon }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { it.setImageDrawable(drawable) },
        modifier = modifier.size(34.dp),
    )
}

@Composable
private fun AppPickerDialog(apps: List<InstalledApp>, selected: String,
                            onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val visible = remember(apps, query) {
        apps.filter {
            it.label.contains(query.trim(), ignoreCase = true) ||
                it.packageName.contains(query.trim(), ignoreCase = true)
        }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Filter Android apps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    label = { Text("Find app by name or package") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    modifier = Modifier.fillMaxWidth())
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                    item {
                        ListItem(
                            headlineContent = { Text("All applications") },
                            leadingContent = { AppIcon("") },
                            trailingContent = {
                                if (selected.isBlank()) Icon(Icons.Rounded.Check, null)
                            },
                            modifier = Modifier.clickable { onPick("") })
                    }
                    items(visible, key = { it.packageName }) { app ->
                        ListItem(
                            headlineContent = { Text(app.label, maxLines = 1) },
                            supportingContent = { Text(app.packageName,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { AppIcon(app.packageName) },
                            trailingContent = {
                                if (app.packageName == selected) Icon(Icons.Rounded.Check, null)
                            },
                            modifier = Modifier.clickable { onPick(app.packageName) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        })
}

/** Android Logcat snapshot viewer; no automatic collection while idle. */
@Composable
internal fun LogcatScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by MonitorState.logs.collectAsState()
    val packages by MonitorState.packages.collectAsState()
    val uids by MonitorState.packageUids.collectAsState()
    val filter by MonitorState.logFilter.collectAsState()
    val active by MonitorState.active.collectAsState()
    val adbVerified by dev.n2k02.mcplogger.adb.AdbCoordinator.verified.collectAsState()
    val deviceLogcatClearing by MonitorState.clearingDeviceLogcat.collectAsState()
    val deviceLogcatStatus by MonitorState.logcatMaintenanceStatus.collectAsState()
    val stats by MonitorState.captureStats.collectAsState()
    val apps by produceState(initialValue = emptyList<InstalledApp>(), key1 = packages) {
        value = withContext(Dispatchers.IO) { resolveApps(context, packages) }
    }
    val filtered = remember(logs, filter, uids, stats.scanCycles) {
        LogViewPolicy.select(logs, filter, uids, System.currentTimeMillis())
    }
    val currentApp = apps.firstOrNull { it.packageName == filter.packageName }
    var showPicker by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var clearConfirmation by remember { mutableStateOf(false) }
    var deviceClearConfirmation by remember { mutableStateOf(false) }
    var timeExpanded by remember { mutableStateOf(false) }
    var levelExpanded by remember { mutableStateOf(false) }
    var actionMenu by remember { mutableStateOf(false) }
    var exportSnapshot by remember { mutableStateOf<List<LogEvent>>(emptyList()) }
    var feedback by remember { mutableStateOf("") }

    fun update(next: LogViewFilter) {
        MonitorState.logFilter.value = next
        MonitorState.selectedPackage.value = next.packageName
    }
    fun copy(title: String, entries: List<LogEvent>) {
        if (entries.isEmpty()) return
        // Avoid Android clipboard Binder size limits; full result is available via Export.
        val selected = entries.takeLast(200)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(title, LogExportFormatter.export(selected))
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = (clip.description.extras ?: android.os.PersistableBundle()).apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        feedback = "Copied " + selected.size + " redacted logs" +
            if (entries.size > selected.size) " (latest 200; Export saves all)" else ""
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val snapshot = exportSnapshot
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val stream = context.contentResolver.openOutputStream(uri)
                            ?: error("Storage destination unavailable")
                        stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write(LogExportFormatter.export(snapshot))
                        }
                        snapshot.size
                    }.getOrDefault(-1)
                }
                feedback = if (saved >= 0)
                    "Saved " + saved + " redacted logs to selected storage"
                else "Could not save logs; select another destination"
            }
        }
    }

    if (showPicker) AppPickerDialog(apps, filter.packageName,
        onPick = { update(filter.copy(packageName = it)); showPicker = false },
        onDismiss = { showPicker = false })
    if (clearConfirmation) {
        AlertDialog(onDismissRequest = { clearConfirmation = false },
            title = { Text("Clear captured Logcat?") },
            text = { Text("Discard the current in-memory logs. New entries appear only after another manual or MCP request. This does not erase Android's system log buffer.") },
            confirmButton = {
                TextButton(onClick = {
                    LogMonitorService.clear()
                    clearConfirmation = false
                    feedback = "Captured logs cleared"
                }) { Text("Clear logs") }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmation = false }) { Text("Cancel") }
            })
    }

    if (deviceClearConfirmation) {
        AlertDialog(
            onDismissRequest = { deviceClearConfirmation = false },
            title = { Text("Clear all Android Logcat buffers?") },
            text = { Text("Runs logcat -b all -c through verified ADB shell UID 2000. " +
                "This deletes logs from ALL apps in Android's system Logcat buffers, " +
                "including crash, events and radio where permitted. It cannot be undone. " +
                "MCP audit history is not deleted. New logs will appear as apps keep running.") },
            confirmButton = {
                TextButton(onClick = {
                    deviceClearConfirmation = false
                    feedback = "Clearing Android Logcat..."
                    context.startService(Intent(context, LogMonitorService::class.java)
                        .setAction(LogMonitorService.CLEAR_DEVICE_LOGCAT))
                }) { Text("Clear device Logcat") }
            },
            dismissButton = {
                TextButton(onClick = { deviceClearConfirmation = false }) { Text("Cancel") }
            })
    }

    Column(modifier.fillMaxSize()) {
        // The large application picker, filter chips, maintenance actions and
        // export buttons are collapsed into two short rows by default.
        Column(Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(filtered.size.toString() + " shown · " + logs.size + " today",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1)
                Text(if (active) "● Reading" else "● Idle",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = {
                    ContextCompat.startForegroundService(context,
                        Intent(context, LogMonitorService::class.java)
                            .setAction(LogMonitorService.START))
                }, enabled = !active && !deviceLogcatClearing && adbVerified,
                    modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh logs from Android")
                }
                Box {
                    IconButton(onClick = { actionMenu = true },
                        modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Log actions")
                    }
                    DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
                        DropdownMenuItem(text = { Text("Copy visible logs") },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                            enabled = filtered.isNotEmpty(), onClick = {
                                actionMenu = false
                                copy("Filtered Android Logcat", filtered)
                            })
                        DropdownMenuItem(text = { Text("Export visible logs") },
                            leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) },
                            enabled = filtered.isNotEmpty(), onClick = {
                                actionMenu = false
                                exportSnapshot = filtered.toList()
                                exportLauncher.launch("Android-Logcat-" +
                                    System.currentTimeMillis().toString() + ".txt")
                            })
                        DropdownMenuItem(text = { Text("Reset filters") },
                            leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                            onClick = {
                                actionMenu = false
                                update(LogViewPolicy.reset())
                                feedback = "All filters reset"
                            })
                        DropdownMenuItem(text = { Text("Clear captured logs") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                            enabled = logs.isNotEmpty(), onClick = {
                                actionMenu = false
                                clearConfirmation = true
                            })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Clear device Logcat (all buffers)") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                            enabled = adbVerified && !deviceLogcatClearing,
                            onClick = {
                                actionMenu = false
                                deviceClearConfirmation = true
                            })
                    }
                }
            }

            // Search remains permanently available. All other controls fit on
            // one narrow filter strip instead of five rows of buttons.
            OutlinedTextField(
                value = filter.search,
                onValueChange = { update(filter.copy(search = it)) },
                placeholder = { Text("Search Logcat…", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(19.dp)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                trailingIcon = {
                    if (filter.search.isNotEmpty())
                        IconButton(onClick = { update(filter.copy(search = "")) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                })

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = filter.packageName.isNotBlank(),
                    onClick = { showPicker = true },
                    label = { Text(if (filter.packageName.isBlank()) "All apps" else
                        currentApp?.label?.take(14) ?: filter.packageName.take(14)) },
                    leadingIcon = { Icon(Icons.Rounded.Apps, null,
                        modifier = Modifier.size(15.dp)) })
                Box {
                    FilterChip(selected = filter.minLevel != "V",
                        onClick = { levelExpanded = true },
                        label = { Text(when (filter.minLevel) {
                            "D" -> "Debug+"; "I" -> "Info+"; "W" -> "Warn+"
                            "E" -> "Error+"; "F" -> "Fatal"; else -> "All levels"
                        }) },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) })
                    DropdownMenu(expanded = levelExpanded, onDismissRequest = { levelExpanded = false }) {
                        listOf("V" to "All", "D" to "Debug +", "I" to "Info +",
                            "W" to "Warning +", "E" to "Error +", "F" to "Fatal").forEach { (level, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                update(filter.copy(minLevel = level))
                                levelExpanded = false
                            })
                        }
                    }
                }
                FilterChip(selected = advanced || filter.crashesOnly ||
                    filter.windowMinutes != 0 || filter.tag.isNotEmpty() || filter.pid.isNotEmpty(),
                    onClick = { advanced = !advanced },
                    label = { Text("Filters") },
                    leadingIcon = { Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(15.dp)) },
                    trailingIcon = {
                        Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    })
            }
            if (advanced) {
                Column(Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = filter.crashesOnly,
                            onClick = { update(filter.copy(crashesOnly = !filter.crashesOnly)) },
                            label = { Text("Crashes / ANRs") })
                        Spacer(Modifier.width(6.dp))
                        Box {
                            TextButton(onClick = { timeExpanded = true }) {
                                Text(when (filter.windowMinutes) {
                                    5 -> "5 min"; 15 -> "15 min"; 60 -> "1 hour"; else -> "Any time"
                                })
                                Icon(Icons.Rounded.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = timeExpanded,
                                onDismissRequest = { timeExpanded = false }) {
                                listOf(0 to "Any time", 5 to "Last 5 min",
                                    15 to "Last 15 min", 60 to "Last hour").forEach { (minutes, name) ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = {
                                        update(filter.copy(windowMinutes = minutes))
                                        timeExpanded = false
                                    })
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = filter.tag,
                            onValueChange = { update(filter.copy(tag = it)) },
                            label = { Text("Tag") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                        OutlinedTextField(value = filter.pid,
                            onValueChange = { update(filter.copy(pid = it.filter(Char::isDigit))) },
                            label = { Text("PID") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
            if (feedback.isNotBlank() || deviceLogcatStatus.isNotBlank()) {
                Text(feedback.ifBlank { deviceLogcatStatus },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.FilterAltOff, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp))
                    Text(if (logs.isEmpty()) "No captured Logcat entries yet"
                        else "No entries match these filters",
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (logs.isEmpty()) "Refresh logs here or request them via MCP after ADB is authorized."
                        else "Change app, level, time or search, or tap Reset filters.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(filtered.asReversed(), key = { it.id }) { event ->
                    LogcatEntryCard(event) { copy("Logcat entry", listOf(event)) }
                }
            }
        }
    }
}

@Composable
private fun LogcatEntryCard(event: LogEvent, onCopy: () -> Unit) {
    var expanded by remember(event.id) { mutableStateOf(false) }
    val tone = when (event.level) {
        "E", "F" -> MaterialTheme.colorScheme.error
        "W" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val timestamp = remember(event.epochMillis) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.epochMillis))
    }
    // Dense Logcat rows prioritize visible text over nested cards and buttons.
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }
        .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(timestamp, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(event.level, color = tone, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium)
            Text(SensitiveLogRedactor.redact(event.tag).take(70),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = tone, modifier = Modifier.weight(1f))
            if (expanded) {
                IconButton(onClick = onCopy, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy log entry",
                        modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(SensitiveLogRedactor.redact(event.message),
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis)
        if (expanded) {
            Text("PID " + event.pid + " · UID " + event.uid +
                " · " + SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                    .format(Date(event.epochMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}
