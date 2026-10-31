package dev.n2k02.mcplogger

import android.content.Context
import android.content.Intent
import android.content.ClipData
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
    var code by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var tcp by remember { mutableStateOf(AdbCoordinator.tcpEnabled(context)) }
    var boot by remember { mutableStateOf(AdbCoordinator.autoStart(context)) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(status, style = MaterialTheme.typography.titleMedium)
        Text("Enable USB debugging and Wireless debugging. On Android, select Pair device with pairing code. Android must approve the first pairing.")
        Text("Discovered pairing port: " + (AdbCoordinator.pairingPort?.toString() ?: "Searching"))
        Text("Discovered connect port: " + (AdbCoordinator.connectPort?.toString() ?: "Searching"))
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) },
            label = { Text("Six-digit pairing code") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pairPort, { pairPort = it.filter(Char::isDigit) },
            label = { Text("Pairing port (optional if discovered)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                val port = pairPort.toIntOrNull() ?: AdbCoordinator.pairingPort
                if (port == null) MonitorState.error("No pairing port found")
                else AdbCoordinator.pair(context, port, code).onSuccess {
                    AdbCoordinator.connect(context).onFailure { MonitorState.error(it.message ?: "ADB failed") }
                }
            }
        }, enabled = code.length == 6) { Text("Pair and verify UID 2000") }
        OutlinedTextField(connectPort, { connectPort = it.filter(Char::isDigit) },
            label = { Text("ADB connect port (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            scope.launch { AdbCoordinator.connect(context, connectPort.toIntOrNull()) }
        }) { Text("Connect / Retry") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automatic TCP/5555", Modifier.weight(1f)); Switch(tcp, onCheckedChange = {
                tcp = it; AdbCoordinator.setTcpEnabled(context, it)
            })
        }
        Text("Warning: TCP/5555 may listen on network interfaces, not just localhost.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recover on boot", Modifier.weight(1f)); Switch(boot, onCheckedChange = {
                boot = it; AdbCoordinator.setAutoStart(context, it)
            })
        }
        Text("Automatic pairing-code detection: " +
            if (dev.n2k02.mcplogger.adb.PairingAccessibilityService.enabled(context))
                "enabled" else "requires Accessibility approval")
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text("Authorize pairing-code helper") }
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }) { Text("Open Developer options") }
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
    var enabled by rememberSaveable { mutableStateOf(saved.third) }
    var confirmed by remember { mutableStateOf(false) }
    var generateConfirmation by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var verification by remember { mutableStateOf("") }
    val mcpUrl = McpConnectionSetup.mcpUrlOrNull(url)
    val status by MonitorState.gateway.collectAsState()

    fun copy(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        verification = "$label copied. Clear the clipboard after use if it contains secrets."
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
                enabled = false
                verification = "Two new keys generated locally. Configure them on your gateway before enabling upload."
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
            verification = ""
        }, label = { Text("Gateway HTTPS URL (base or /mcp)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(key, { key = it }, label = { Text("Device API key (for log uploads)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(clientToken, { clientToken = it },
            label = { Text("MCP client token (for Claude / ChatGPT)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation())

        OutlinedButton(onClick = { generateConfirmation = true }) {
            Icon(Icons.Rounded.AutoAwesome, null)
            Spacer(Modifier.width(8.dp))
            Text("Generate secure keys")
        }
        Text("The keys are generated on your phone, not registered automatically. Use Copy server configuration to set matching DEVICE_API_KEY and MCP_CLIENT_TOKEN on your hosted gateway.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            copy("Gateway server configuration",
                McpConnectionSetup.dockerEnvironment(key, clientToken))
        }, enabled = key.isNotBlank() && clientToken.isNotBlank()) {
            Text("Copy server configuration")
        }

        if (mcpUrl != null) {
            HorizontalDivider()
            Text("MCP URL to paste into Claude / ChatGPT",
                style = MaterialTheme.typography.titleSmall)
            Text(mcpUrl, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy("MCP URL", mcpUrl) }) {
                    Text("Copy MCP URL")
                }
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable log upload", Modifier.weight(1f))
            Switch(enabled, onCheckedChange = { enabled = it })
        }
        Text("Uploads are opt-in. An MCP URL alone does not start a gateway, create an account or authorize AI access.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            runCatching {
                BridgeClient.configure(context, url, key, enabled)
                BridgeClient.saveClientToken(context, clientToken)
                confirmed = true
                verification = "Saved. Start log monitoring to upload after the gateway credentials match."
            }.onFailure {
                verification = "Invalid gateway configuration: use an HTTPS URL and device API key."
                MonitorState.error("Invalid HTTPS gateway configuration")
            }
        }) { Text("Save MCP connection") }
        if (confirmed) Text("MCP settings saved on this phone.",
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
