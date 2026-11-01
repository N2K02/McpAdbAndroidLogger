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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

private enum class LogcatTab(val title: String) {
    LOGS("Logs"), FILTERS("Filters"), STORAGE("Storage")
}

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
    var tab by rememberSaveable { mutableStateOf(LogcatTab.LOGS) }
    var clearConfirmation by remember { mutableStateOf(false) }
    var deviceClearConfirmation by remember { mutableStateOf(false) }
    var timeExpanded by remember { mutableStateOf(false) }
    var levelExpanded by remember { mutableStateOf(false) }
    var quickLevelExpanded by remember { mutableStateOf(false) }
    var quickTimeExpanded by remember { mutableStateOf(false) }
    var actionMenu by remember { mutableStateOf(false) }
    val recentFirst = remember(filtered) { filtered.asReversed() }
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
        // Portrait-first: filter/storage controls never occupy the Logcat viewport.
        TabRow(selectedTabIndex = tab.ordinal) {
            LogcatTab.entries.forEach { destination ->
                Tab(selected = tab == destination, onClick = { tab = destination },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(when (destination) {
                                LogcatTab.LOGS -> Icons.Rounded.Terminal
                                LogcatTab.FILTERS -> Icons.Rounded.FilterAlt
                                LogcatTab.STORAGE -> Icons.Rounded.Storage
                            }, null, modifier = Modifier.size(16.dp))
                            Text(destination.title, maxLines = 1,
                                style = MaterialTheme.typography.labelLarge)
                        }
                    })
            }
        }
        when (tab) {
            LogcatTab.LOGS -> {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(filtered.size.toString() + " shown · " + logs.size + " today",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, modifier = Modifier.weight(1f))
                    Text(if (active) "● Reading" else "● Idle",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = {
                        ContextCompat.startForegroundService(context,
                            Intent(context, LogMonitorService::class.java)
                                .setAction(LogMonitorService.START))
                    }, enabled = !active && !deviceLogcatClearing && adbVerified) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh logs")
                    }
                    Box {
                        IconButton(onClick = { actionMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Log actions")
                        }
                        DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
                            DropdownMenuItem(text = { Text("Copy visible logs") },
                                enabled = filtered.isNotEmpty(), onClick = {
                                    actionMenu = false
                                    copy("Filtered Android Logcat", filtered)
                                })
                            DropdownMenuItem(text = { Text("Export visible logs") },
                                enabled = filtered.isNotEmpty(), onClick = {
                                    actionMenu = false
                                    exportSnapshot = filtered.toList()
                                    exportLauncher.launch("Android-Logcat-" +
                                        System.currentTimeMillis().toString() + ".txt")
                                })
                            DropdownMenuItem(text = { Text("Reset filters") }, onClick = {
                                actionMenu = false
                                update(LogViewPolicy.reset())
                            })
                            DropdownMenuItem(text = { Text("Clear captured logs") },
                                enabled = logs.isNotEmpty(), onClick = {
                                    actionMenu = false
                                    clearConfirmation = true
                                })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Open Filters") }, onClick = {
                                actionMenu = false
                                tab = LogcatTab.FILTERS
                            })
                            DropdownMenuItem(text = { Text("Storage & retention") }, onClick = {
                                actionMenu = false
                                tab = LogcatTab.STORAGE
                            })
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.height(46.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        BasicTextField(value = filter.search,
                            onValueChange = { update(filter.copy(search = it)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface),
                            decorationBox = { inner ->
                                Box {
                                    if (filter.search.isBlank())
                                        Text("Search logs, tags or PID",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    inner()
                                }
                            })
                        if (filter.search.isNotBlank()) {
                            IconButton(onClick = { update(filter.copy(search = "")) },
                                modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear search",
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = filter.packageName.isNotBlank(),
                        onClick = { showPicker = true },
                        label = { Text(if (filter.packageName.isBlank()) "App: All" else
                            (currentApp?.label ?: filter.packageName).take(16)) },
                        leadingIcon = { Icon(Icons.Rounded.Apps, null,
                            modifier = Modifier.size(16.dp)) })
                    Box {
                        FilterChip(selected = filter.minLevel != "V",
                            onClick = { quickLevelExpanded = true },
                            label = { Text("Level: " + when (filter.minLevel) {
                                "D" -> "Debug+"; "I" -> "Info+"; "W" -> "Warn+"
                                "E" -> "Error+"; "F" -> "Fatal"; else -> "All"
                            }) },
                            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) })
                        DropdownMenu(expanded = quickLevelExpanded,
                            onDismissRequest = { quickLevelExpanded = false }) {
                            listOf("V" to "All", "D" to "Debug +", "I" to "Info +",
                                "W" to "Warning +", "E" to "Error +", "F" to "Fatal")
                                .forEach { (code, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                        update(filter.copy(minLevel = code))
                                        quickLevelExpanded = false
                                    })
                                }
                        }
                    }
                    Box {
                        FilterChip(selected = filter.windowMinutes != 0,
                            onClick = { quickTimeExpanded = true },
                            label = { Text("Time: " + when (filter.windowMinutes) {
                                5 -> "5m"; 15 -> "15m"; 60 -> "1h"; else -> "Any"
                            }) },
                            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) })
                        DropdownMenu(expanded = quickTimeExpanded,
                            onDismissRequest = { quickTimeExpanded = false }) {
                            listOf(0 to "Any time", 5 to "Last 5 minutes",
                                15 to "Last 15 minutes", 60 to "Last hour")
                                .forEach { (minutes, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                        update(filter.copy(windowMinutes = minutes))
                                        quickTimeExpanded = false
                                    })
                                }
                        }
                    }
                    FilterChip(selected = filter.crashesOnly,
                        onClick = { update(filter.copy(crashesOnly = !filter.crashesOnly)) },
                        label = { Text("Crashes/ANRs") },
                        leadingIcon = { Icon(Icons.Rounded.BugReport, null,
                            modifier = Modifier.size(16.dp)) })
                }
                if (feedback.isNotBlank())
                    Text(feedback, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp))
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
                items(recentFirst, key = { it.id }) { event ->
                    LogcatEntryCard(event) { copy("Logcat entry", listOf(event)) }
                }
            }
        }
            }
            LogcatTab.FILTERS -> {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Filter today's captured logs", style = MaterialTheme.typography.titleMedium)
                    OutlinedCard(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(filter.packageName)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(currentApp?.label ?: if (filter.packageName.isBlank())
                                    "All applications" else filter.packageName,
                                    maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                Text("Filter by installed app", style = MaterialTheme.typography.labelSmall)
                            }
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose application")
                        }
                    }
                    Text("Minimum severity", style = MaterialTheme.typography.labelMedium)
                    Box {
                        OutlinedButton(onClick = { levelExpanded = true },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(when (filter.minLevel) {
                                "D" -> "Debug +"; "I" -> "Info +"; "W" -> "Warn +"
                                "E" -> "Error +"; "F" -> "Fatal"; else -> "All levels"
                            })
                            Icon(Icons.Rounded.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = levelExpanded,
                            onDismissRequest = { levelExpanded = false }) {
                            listOf("V" to "All", "D" to "Debug +", "I" to "Info +",
                                "W" to "Warning +", "E" to "Error +", "F" to "Fatal")
                                .forEach { (code, title) ->
                                    DropdownMenuItem(text = { Text(title) }, onClick = {
                                        update(filter.copy(minLevel = code))
                                        levelExpanded = false
                                    })
                                }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Crashes / ANRs", modifier = Modifier.weight(1f))
                        Switch(checked = filter.crashesOnly,
                            onCheckedChange = { update(filter.copy(crashesOnly = it)) })
                    }
                    Text("Time window", style = MaterialTheme.typography.labelMedium)
                    Box {
                        OutlinedButton(onClick = { timeExpanded = true },
                            modifier = Modifier.fillMaxWidth()) {
                            Text(when (filter.windowMinutes) {
                                5 -> "Last 5 minutes"; 15 -> "Last 15 minutes"
                                60 -> "Last hour"; else -> "Any time today"
                            })
                            Icon(Icons.Rounded.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = timeExpanded,
                            onDismissRequest = { timeExpanded = false }) {
                            listOf(0 to "Any time today", 5 to "Last 5 minutes",
                                15 to "Last 15 minutes", 60 to "Last hour")
                                .forEach { (minutes, title) ->
                                    DropdownMenuItem(text = { Text(title) }, onClick = {
                                        update(filter.copy(windowMinutes = minutes))
                                        timeExpanded = false
                                    })
                                }
                        }
                    }
                    OutlinedTextField(value = filter.tag,
                        onValueChange = { update(filter.copy(tag = it.take(120))) },
                        label = { Text("Tag contains") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = filter.pid,
                        onValueChange = { update(filter.copy(pid = it.filter(Char::isDigit))) },
                        label = { Text("Process ID") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { update(LogViewPolicy.reset()) },
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.RestartAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Reset filters")
                    }
                    OutlinedButton(onClick = { tab = LogcatTab.LOGS },
                        modifier = Modifier.fillMaxWidth()) { Text("View logs") }
                }
            }
            LogcatTab.STORAGE -> {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Today’s private Logcat cache", style = MaterialTheme.typography.titleMedium)
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("Daily reset enabled", style = MaterialTheme.typography.titleSmall)
                            Text("Today only · " + logs.size + " / 2,000 buffered entries")
                            Text("Auto reset at local midnight (00:00)")
                            Text("Read cycles today: " + stats.scanCycles)
                            Text("Older cached logs are pruned while the service runs. " +
                                "No background Logcat collection or automatic device-wide clearing.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedButton(onClick = {
                        copy("Filtered Android Logcat", filtered)
                    }, enabled = filtered.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.ContentCopy, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Copy visible logs")
                    }
                    Button(onClick = {
                        exportSnapshot = filtered.toList()
                        exportLauncher.launch("Android-Logcat-" +
                            System.currentTimeMillis().toString() + ".txt")
                    }, enabled = filtered.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.SaveAlt, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Export visible logs")
                    }
                    OutlinedButton(onClick = { clearConfirmation = true },
                        enabled = logs.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.DeleteOutline, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Clear captured logs")
                    }
                    HorizontalDivider()
                    OutlinedButton(onClick = { deviceClearConfirmation = true },
                        enabled = adbVerified && !deviceLogcatClearing,
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.DeleteSweep, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Clear device Logcat (all buffers)")
                    }
                    Text("Device Logcat clearing requires confirmation. It deletes Android " +
                        "system buffers, not MCP audit history. Daily reset never does this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (deviceLogcatStatus.isNotBlank()) Text(deviceLogcatStatus)
                    if (feedback.isNotBlank()) Text(feedback)
                }
            }
        }
    }
}

@Composable
private fun LogcatEntryCard(event: LogEvent, onCopy: () -> Unit) {
    var expanded by remember(event.id) { mutableStateOf(false) }
    val severity = remember(event.level) { LogSeverity.title(event.level) }
    val tone = when (event.level) {
        "F" -> Color(0xFFFF6478)
        "E" -> Color(0xFFFF8B73)
        "W" -> Color(0xFFF6C45B)
        "I" -> Color(0xFF6FD8A4)
        "D" -> Color(0xFF80BEFF)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val timestamp = remember(event.epochMillis) {
        SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(event.epochMillis))
    }
    val detailedTime = remember(event.epochMillis) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date(event.epochMillis))
    }
    val safeTag = remember(event.tag) { SensitiveLogRedactor.redact(event.tag).take(120) }
    val safeMessage = remember(event.message) { SensitiveLogRedactor.redact(event.message) }
    val collapsedMessage = remember(safeMessage) {
        safeMessage.replace('\n', ' ').replace('\r', ' ').trim()
    }
    // A single-line summary is always tap-to-expand, with a chevron on every row.
    Row(Modifier.fillMaxWidth()
        .clickable { expanded = !expanded }
        .heightIn(min = 44.dp)
        .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Surface(shape = RoundedCornerShape(6.dp),
            color = tone.copy(alpha = 0.17f)) {
            Text(LogSeverity.code(event.level), color = tone,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
        }
        Text(timestamp, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(safeTag, style = MaterialTheme.typography.labelSmall,
            color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.37f))
        Text(collapsedMessage, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.63f))
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "Collapse log details" else "Expand log details",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) {
        Surface(modifier = Modifier.fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, tone.copy(alpha = 0.4f))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(severity, color = tone, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text(detailedTime, style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = onCopy) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy log entry",
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                }
                Text("Tag: " + safeTag, style = MaterialTheme.typography.labelMedium)
                Text("PID " + event.pid + "  ·  UID " + event.uid,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                SelectionContainer {
                    Text(safeMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    HorizontalDivider(thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f))
}
