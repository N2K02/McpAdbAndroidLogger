package dev.n2k02.mcplogger

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.content.ClipboardManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.n2k02.mcplogger.adb.AdbCoordinator
import dev.n2k02.mcplogger.core.BridgeClient
import dev.n2k02.mcplogger.core.McpConnectionSetup
import dev.n2k02.mcplogger.core.LogMonitorService
import dev.n2k02.mcplogger.core.MonitorState
import dev.n2k02.updater.N2K02UpdatePage
import dev.n2k02.updater.N2K02UpdateBadge
import dev.n2k02.updater.N2K02UpdateBadgedIcon
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggerApp(onStart: () -> Unit, onStop: () -> Unit, onQuit: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var subpageParent by rememberSaveable { mutableStateOf(Page.SETTINGS) }
    fun navigateTo(destination: Page) {
        val next = LoggerNavigation(page, subpageParent).open(destination)
        page = next.page
        subpageParent = next.parent
    }
    fun navigateBack() {
        val next = LoggerNavigation(page, subpageParent).back()
        page = next.page
        subpageParent = next.parent
    }
    // mRadio-style system/predictive Back: subpage -> parent, Settings -> Home.
    // Home does not intercept Back, so Android keeps its normal exit behavior.
    BackHandler(enabled = page != Page.HOME) { navigateBack() }

    val context = LocalContext.current
    val status by LiveUpdater.controller.status.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (page) {
                                Page.HOME -> Icons.Rounded.Home
                                Page.SETTINGS -> Icons.Rounded.Settings
                                Page.ADB -> Icons.Rounded.SettingsInputAntenna
                                Page.BRIDGE -> Icons.Rounded.Cloud
                                Page.LOGS -> Icons.Rounded.List
                                Page.DIAGNOSTICS -> Icons.Rounded.BugReport
                                Page.UPDATES -> Icons.Rounded.SystemUpdate
                            }, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(page.title, style = MaterialTheme.typography.titleMedium)
                    }
                },
                navigationIcon = if (page !in listOf(Page.HOME, Page.SETTINGS)) {
                    { IconButton(onClick = { navigateBack() }) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    } }
                } else { {} },
                actions = {
                    Icon(painter = painterResource(R.drawable.ic_app_mark),
                        contentDescription = "MCP Logger", tint = Color.Unspecified,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("MCP Logger", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(12.dp))
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = LoggerNavigation(page, subpageParent).homeSelected,
                    onClick = { navigateTo(Page.HOME) },
                    icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") })
                NavigationBarItem(selected = LoggerNavigation(page, subpageParent).settingsSelected,
                    onClick = { navigateTo(Page.SETTINGS) },
                    icon = { N2K02UpdateBadgedIcon(status) {
                        Icon(Icons.Rounded.Settings, null) } }, label = { Text("Settings") })
                NavigationBarItem(selected = false, onClick = { onQuit(); navigateTo(Page.HOME) },
                    icon = { Icon(Icons.Rounded.PowerSettingsNew, null) }, label = { Text("Quit") })
            }
        }
    ) { padding ->
        when (page) {
            Page.HOME -> HomePage(Modifier.padding(padding), onStart, onStop,
                onAdb = { navigateTo(Page.ADB) }, onLogs = { navigateTo(Page.LOGS) })
            Page.SETTINGS -> Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
                SettingsRow("ADB connection", "Pair, authorize and recover shell UID 2000",
                    Icons.Rounded.SettingsInputAntenna) { navigateTo(Page.ADB) }
                SettingsRow("MCP connection", "Secure outbound HTTPS gateway",
                    Icons.Rounded.Cloud) { navigateTo(Page.BRIDGE) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingsRow("Diagnostics", "Connection status and failures",
                    Icons.Rounded.BugReport) { navigateTo(Page.DIAGNOSTICS) }
                SettingsRow("Logs", "Search, filter and review Logcat",
                    Icons.Rounded.List) { navigateTo(Page.LOGS) }
                ListItem(headlineContent = { Text("About & updates") },
                    supportingContent = { Text("Stable and experimental APK updates") },
                    leadingContent = { Icon(Icons.Rounded.SystemUpdate, null) },
                    trailingContent = { N2K02UpdateBadge(status) },
                    modifier = Modifier.clickable { navigateTo(Page.UPDATES) })
            }
            Page.ADB -> AdbPage(Modifier.padding(padding))
            Page.BRIDGE -> BridgePage(Modifier.padding(padding))
            Page.LOGS -> LogsPage(Modifier.padding(padding))
            Page.DIAGNOSTICS -> DiagnosticsPage(Modifier.padding(padding))
            Page.UPDATES -> {
                val experiment by LiveUpdater.experimentalEnabled.collectAsState()
                N2K02UpdatePage(
                    controller = LiveUpdater.controller,
                    installGuard = LiveUpdater.installGuard,
                    experimentalEnabled = experiment,
                    onExperimentalEnabledChange = LiveUpdater::setExperimentalEnabled,
                    modifier = Modifier.padding(padding))
            }
        }
    }
}
@Composable
private fun SettingsRow(title: String, subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
        modifier = Modifier.clickable(onClick = onClick))
}

@Composable
private fun HomePage(modifier: Modifier, onStart: () -> Unit, onStop: () -> Unit,
    onAdb: () -> Unit, onLogs: () -> Unit) {
    val active by MonitorState.active.collectAsState()
    val adb by MonitorState.adb.collectAsState()
    val bridge by MonitorState.gateway.collectAsState()
    val events by MonitorState.logs.collectAsState()
    val selected by MonitorState.selectedPackage.collectAsState()
    val packages by MonitorState.packages.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(onClick = onAdb, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("ADB shell", style = MaterialTheme.typography.titleMedium)
                Text(adb)
                Text(if (adb.contains("2000")) "✓ Shell UID 2000" else "✕ Shell not verified")
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Monitoring", style = MaterialTheme.typography.titleMedium)
                Text(if (active) "Running" else "Stopped")
                Text("AI bridge: " + bridge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart, enabled = !active) { Text("Start monitoring") }
                    OutlinedButton(onClick = onStop, enabled = active) { Text("Stop") }
                }
            }
        }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(value = if (selected.isBlank()) "All apps" else selected,
                onValueChange = {}, readOnly = true,
                label = { Text("Filter application") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("All apps") }, onClick = {
                    MonitorState.selectedPackage.value = ""; expanded = false
                })
                packages.forEach { pkg ->
                    DropdownMenuItem(text = { Text(pkg) }, onClick = {
                        MonitorState.selectedPackage.value = pkg; expanded = false
                    })
                }
            }
        }
        ElevatedCard(onClick = onLogs) {
            Column(Modifier.padding(16.dp)) {
                Text("Recent logs", style = MaterialTheme.typography.titleMedium)
                Text(events.size.toString() + " buffered events")
                events.takeLast(6).forEach { Text(it.level + " / " + it.tag + ": " +
                    it.message.take(130), style = MaterialTheme.typography.bodySmall) }
                Text("Open Logs", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun AdbPage(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by MonitorState.adb.collectAsState()
    val discovery by AdbCoordinator.discoveryState.collectAsState()
    val verified = status.startsWith("UID 2000 verified")
    val pairingPort = discovery.pairingPort
    val discoveredPort = discovery.connectPort
    var code by remember { mutableStateOf("") }
    var pairPort by rememberSaveable { mutableStateOf("") }
    var connectPort by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var tcp by remember { mutableStateOf(AdbCoordinator.tcpEnabled(context)) }
    var boot by remember { mutableStateOf(AdbCoordinator.autoStart(context)) }
    val autoCode = dev.n2k02.mcplogger.adb.PairingAccessibilityService.enabled(context)

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (verified) Icons.Rounded.CheckCircle else Icons.Rounded.SettingsInputAntenna,
                        null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("ADB connection", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    AssistChip(onClick = {}, label = {
                        Text(if (verified) "UID 2000 ✓" else "Not ready")
                    })
                }
                Text(if (verified) "Authorized Android shell is ready" else
                    "First-time setup: enable Wireless debugging and pair once.",
                    style = MaterialTheme.typography.bodyMedium)
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text(discovery.status, style = MaterialTheme.typography.bodySmall)
                Text("Active: " + (if (verified) status.substringAfter("• ").trim()
                    else "Disconnected") +
                    "   ·   Wireless TLS: " + (discoveredPort?.toString() ?: "not found"),
                    style = MaterialTheme.typography.bodySmall)
                if (pairingPort != null && !verified) {
                    Text("Pairing listener: $pairingPort", style = MaterialTheme.typography.bodySmall)
                }
                if (!verified) {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        }.onFailure { message = "Developer options are unavailable on this device." }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Settings, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open Android Developer options")
                    }
                    Text("1. Enable USB debugging and Wireless debugging (Wi-Fi required).\n" +
                        "2. Choose Pair device with pairing code in Android Settings.\n" +
                        "3. Pair here or let the optional pairing-code helper read the visible Android dialog.\n" +
                        "4. The app connects, verifies UID 2000 and switches to TCP/5555 if enabled.",
                        style = MaterialTheme.typography.bodySmall)
                    if (autoCode) {
                        Text("Automatic pairing-code detection is enabled.",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }.onFailure { message = "Accessibility Settings unavailable." }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Enable automatic pairing-code detection")
                        }
                        Text("Optional: the helper reads only the visible Android pairing dialog.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    // VRec-style guided entry: show the code field after Android publishes
                    // a pairing listener, or when the user expands manual port controls.
                    if (pairingPort != null || expanded) {
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) },
                            label = { Text("Six-digit pairing code") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                        Button(onClick = {
                            working = true
                            scope.launch {
                                try {
                                    val port = pairPort.toIntOrNull() ?: pairingPort
                                    if (port == null) {
                                        message = "Open Android's pairing code dialog first."
                                    } else {
                                        val result = AdbCoordinator.pair(context, port, code)
                                        code = ""
                                        message = if (result.isSuccess) {
                                            val connected = AdbCoordinator.connect(context)
                                            if (connected.isSuccess) "ADB connected, UID 2000 verified."
                                            else "Paired; enable Wireless debugging to connect."
                                        } else "Pairing failed: " + (result.exceptionOrNull()?.message ?: "Unknown error")
                                    }
                                } finally { working = false }
                            }
                        }, enabled = code.length == 6 && !working, modifier = Modifier.fillMaxWidth()) {
                            Text(if (working) "Pairing..." else "Pair and connect ADB")
                        }
                    }
                }
                OutlinedButton(onClick = {
                    working = true
                    scope.launch {
                        try {
                            val result = AdbCoordinator.connect(context, connectPort.toIntOrNull())
                            message = if (result.isSuccess) "UID 2000 verified."
                                else "Could not connect: " + (result.exceptionOrNull()?.message ?: "not authorized")
                        } finally { working = false }
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !working) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (working) "Connecting..." else "Reconnect / verify ADB")
                }
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connection recovery", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Automatic TCP/5555 after pairing", Modifier.weight(1f))
                    Switch(tcp, onCheckedChange = {
                        tcp = it
                        AdbCoordinator.setTcpEnabled(context, it)
                    })
                }
                Text("Enabled by default, like VRec. TCP/5555 may be reachable over your LAN; turn it off if you don't need it.",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recover on boot", Modifier.weight(1f))
                    Switch(boot, onCheckedChange = {
                        boot = it
                        AdbCoordinator.setAutoStart(context, it)
                    })
                }
            }
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide advanced port controls" else "Advanced: manual ports")
            Spacer(Modifier.width(8.dp))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        if (expanded) {
            OutlinedTextField(pairPort, { pairPort = it.filter(Char::isDigit).take(5) },
                label = { Text("Manual pairing port (optional)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(connectPort, { connectPort = it.filter(Char::isDigit).take(5) },
                label = { Text("Manual connect port (optional)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Manual ports are fallback values. Auto-discovered ports are preferred.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BridgePage(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { BridgeClient.settings(context) }
    var url by rememberSaveable { mutableStateOf(saved.first) }
    var key by rememberSaveable { mutableStateOf(saved.second) }
    var clientToken by rememberSaveable { mutableStateOf(BridgeClient.clientToken(context)) }
    var generateConfirmation by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var verification by remember { mutableStateOf("") }
    val mcpUrl = McpConnectionSetup.mcpUrlOrNull(url)
    val status by MonitorState.gateway.collectAsState()

    fun save() = BridgeClient.autoSave(context, url, key, clientToken)

    fun copy(label: String, value: String, sensitive: Boolean = false) {
        if (value.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            // Hide key previews in Android's clipboard overlay.
            clip.description.extras = (clip.description.extras ?: android.os.PersistableBundle()).apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        verification = if (sensitive) "$label copied. Clear the clipboard after use."
            else "$label copied."
    }
    fun openSite(address: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(address)))
        }.onFailure { verification = "No browser available to open connector settings." }
    }
    if (generateConfirmation) {
        AlertDialog(
            onDismissRequest = { generateConfirmation = false },
            title = { Text("Replace MCP credentials?") },
            text = { Text("New secrets must also be configured on your gateway server. Existing uploads and MCP access will stop until the server uses them.") },
            confirmButton = { TextButton(onClick = {
                key = McpConnectionSetup.newSecret()
                clientToken = McpConnectionSetup.newSecret()
                save()
                verification = "Keys rotated and saved. Update both keys on your gateway server to restore access."
                generateConfirmation = false
            }) { Text("Generate") } },
            dismissButton = { TextButton(onClick = { generateConfirmation = false }) {
                Text("Cancel")
            } },
        )
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bridge: $status", style = MaterialTheme.typography.titleMedium)
        Text("Connect a hosted MCP gateway to Claude or ChatGPT. This phone cannot invent an internet-reachable address: deploy the included gateway first or provide an existing HTTPS gateway URL.")
        OutlinedTextField(url, {
            url = it
            save()
            verification = ""
        }, label = { Text("Gateway HTTPS URL (base or /mcp)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            trailingIcon = {
                IconButton(onClick = { copy("Gateway URL", url) }, enabled = url.isNotBlank()) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy gateway URL")
                }
            })
        OutlinedTextField(key, { key = it; save() }, label = { Text("Device API key (for log uploads)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { copy("Device API key", key, sensitive = true) },
                    enabled = key.isNotBlank()) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy device API key")
                }
            })
        OutlinedTextField(clientToken, { clientToken = it; save() },
            label = { Text("MCP client token (for Claude / ChatGPT)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { copy("MCP client token", clientToken, sensitive = true) },
                    enabled = clientToken.isNotBlank()) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy MCP client token")
                }
            })

        OutlinedButton(onClick = { generateConfirmation = true }) {
            Icon(Icons.Rounded.AutoAwesome, null)
            Spacer(Modifier.width(8.dp))
            Text("Regenerate secure keys")
        }
        Text("Keys are generated on first install, then saved automatically. The gateway must use matching DEVICE_API_KEY and MCP_CLIENT_TOKEN values.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            copy("Gateway server configuration",
                McpConnectionSetup.dockerEnvironment(key, clientToken))
        }, enabled = key.isNotBlank() && clientToken.isNotBlank()) {
            Text("Copy server configuration")
        }

        FilledTonalButton(onClick = {
            if (mcpUrl == null) {
                verification = "A shareable MCP URL needs a deployed HTTPS gateway. Set its address first; a random URL cannot connect by itself."
            } else {
                copy("MCP URL", mcpUrl)
            }
        }) {
            Icon(Icons.Rounded.Link, null)
            Spacer(Modifier.width(8.dp))
            Text("Generate / copy MCP URL")
        }

        if (mcpUrl != null) {
            HorizontalDivider()
            Text("MCP URL to paste into Claude / ChatGPT",
                style = MaterialTheme.typography.titleSmall)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(mcpUrl, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                IconButton(onClick = { copy("MCP URL", mcpUrl) }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy MCP URL")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    checking = true
                    verification = "Checking gateway..."
                    scope.launch {
                        val ok = BridgeClient.checkGateway(url)
                        verification = if (ok) "Gateway is reachable. This does not verify the client token or register your connector."
                            else "Gateway unavailable or not an MCP ADB Logger server. Deploy the gateway and verify HTTPS."
                        checking = false
                    }
                }, enabled = !checking) { Text("Test gateway") }
            }
            OutlinedButton(onClick = {
                copy("MCP Authorization header", "Bearer $clientToken")
            }, enabled = clientToken.isNotBlank()) {
                Text("Copy MCP bearer token")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { openSite("https://claude.ai") }) {
                    Text("Open Claude")
                }
                TextButton(onClick = { openSite("https://chatgpt.com/plugins") }) {
                    Text("Open ChatGPT plugins")
                }
            }
            Text("In Claude: Customize → Connectors → Add custom connector. In ChatGPT: Plugins → Add custom MCP server (when available for your account). Enter the MCP URL and authenticate using the MCP client token. You must approve the connection in Claude or ChatGPT.",
                style = MaterialTheme.typography.bodySmall)
        } else if (url.isNotBlank()) {
            Text("Enter a valid HTTPS gateway address, without embedded credentials.",
                color = MaterialTheme.colorScheme.error)
        }
        if (verification.isNotBlank()) {
            Text(verification, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("Configuration saved automatically", style = MaterialTheme.typography.titleSmall)
        Text(if (McpConnectionSetup.gatewayBaseOrNull(url) != null && key.isNotBlank())
            "Log upload is enabled by default and starts with monitoring. The server must accept the generated device key."
        else "Enter a deployed HTTPS gateway URL. Upload stays inactive until the address is valid.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LogsPage(modifier: Modifier) {
    val logs by MonitorState.logs.collectAsState()
    var filter by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(filter, { filter = it }, label = { Text("Search logs") },
                modifier = Modifier.weight(1f), singleLine = true)
            TextButton(onClick = { LogMonitorService.clear() }) { Text("Clear") }
        }
        LazyColumn {
            items(logs.filter {
                filter.isBlank() || it.raw.contains(filter, ignoreCase = true)
            }.takeLast(700).reversed(), key = { it.id }) { line ->
                ListItem(headlineContent = { Text(line.level + " • " + line.tag) },
                    supportingContent = { Text(line.message) },
                    overlineContent = { Text(line.epochMillis.toString() + " • UID " + line.uid) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DiagnosticsPage(modifier: Modifier) {
    val failures by MonitorState.errors.collectAsState()
    val adb by MonitorState.adb.collectAsState()
    val bridge by MonitorState.gateway.collectAsState()
    val active by MonitorState.active.collectAsState()
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("ADB: " + adb)
        Text("MCP: " + bridge)
        Text("Log collection: " + if (active) "running" else "stopped")
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        failures.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp)) }
    }
}
