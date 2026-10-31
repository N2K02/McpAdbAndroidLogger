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
                                Page.UPDATES -> Icons.Rounded.SystemUpdate
                            }, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(page.title, style = MaterialTheme.typography.titleMedium)
                    }
                },
                navigationIcon = if (page !in listOf(Page.HOME, Page.LOGS, Page.SETTINGS)) {
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
                NavigationBarItem(selected = LoggerNavigation(page, subpageParent).logsSelected,
                    onClick = { navigateTo(Page.LOGS) },
                    icon = { Icon(Icons.Rounded.Terminal, null) },
                    label = { Text("Logs") })
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
                onAdb = { navigateTo(Page.ADB) }, onMcp = { navigateTo(Page.BRIDGE) })
            Page.SETTINGS -> Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
                SettingsRow("ADB connection", "Pair, authorize and recover shell UID 2000",
                    Icons.Rounded.SettingsInputAntenna) { navigateTo(Page.ADB) }
                SettingsRow("MCP connection", "Local MCP server and secure public tunnel",
                    Icons.Rounded.Cloud) { navigateTo(Page.BRIDGE) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ListItem(headlineContent = { Text("About & updates") },
                    supportingContent = { Text("Stable and experimental APK updates") },
                    leadingContent = { Icon(Icons.Rounded.SystemUpdate, null) },
                    trailingContent = { N2K02UpdateBadge(status) },
                    modifier = Modifier.clickable { navigateTo(Page.UPDATES) })
            }
            Page.ADB -> AdbPage(Modifier.padding(padding))
            Page.BRIDGE -> BridgePage(Modifier.padding(padding))
            Page.LOGS -> LogsPage(Modifier.padding(padding))
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
    onAdb: () -> Unit, onMcp: () -> Unit) {
    HomeDashboard(modifier, onStart, onStop, onAdb, onMcp)
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
    val local by MonitorState.localMcp.collectAsState()
    val tunnel by MonitorState.tunnel.collectAsState()
    val publicUrl by MonitorState.publicMcpUrl.collectAsState()
    var clientToken by rememberSaveable {
        mutableStateOf(BridgeClient.clientToken(context))
    }
    var hint by remember { mutableStateOf("") }
    val localUrl = dev.n2k02.mcplogger.core.LocalMcpServer.URL
    var rotating by remember { mutableStateOf(false) }

    fun copy(label: String, value: String, sensitive: Boolean = false) {
        if (value.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = (clip.description.extras ?: android.os.PersistableBundle()).apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        hint = if (sensitive) "$label copied. Clear the clipboard after use." else "$label copied."
    }

    fun sendAction(action: String) {
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, LogMonitorService::class.java).setAction(action))
        }.onFailure { hint = "Cannot start MCP service: " + it.javaClass.simpleName }
    }

    if (rotating) AlertDialog(
        onDismissRequest = { rotating = false },
        title = { Text("Rotate MCP access key?") },
        text = { Text("The previous MCP key will stop working immediately. Update the key in Claude and ChatGPT.") },
        confirmButton = { TextButton(onClick = {
            clientToken = McpConnectionSetup.newSecret()
            BridgeClient.saveClientToken(context, clientToken)
            rotating = false
        }) { Text("Rotate key") } },
        dismissButton = { TextButton(onClick = { rotating = false }) { Text("Cancel") } },
    )

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Phone-hosted MCP", style = MaterialTheme.typography.titleMedium)
        Text("The MCP server runs on this Android phone. Only read-only, redacted Logcat diagnostics are accessible with your private token.")
        Text("Local server: $local", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = localUrl, onValueChange = {}, readOnly = true,
            label = { Text("Local MCP URL (other apps on this phone)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { copy("Local MCP URL", localUrl) }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy local MCP URL")
                }
            })
        HorizontalDivider()
        Text("Public MCP for Claude / ChatGPT", style = MaterialTheme.typography.titleMedium)
        Text("Quick Tunnel: $tunnel", style = MaterialTheme.typography.bodySmall)
        if (publicUrl.isBlank()) {
            Text("A public URL appears here only when Cloudflare confirms a tunnel connection. It changes when the tunnel restarts; it requires the phone to be online.",
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(value = publicUrl, onValueChange = {}, readOnly = true,
            label = { Text("Public HTTPS MCP URL") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            trailingIcon = {
                IconButton(onClick = { copy("Public MCP URL", publicUrl) },
                    enabled = publicUrl.isNotBlank()) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy public MCP URL")
                }
            })
        OutlinedTextField(value = clientToken,
            onValueChange = {
                clientToken = it
                BridgeClient.saveClientToken(context, it)
            }, label = { Text("MCP client access key") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = {
                    copy("MCP client access key", clientToken, sensitive = true)
                }, enabled = clientToken.isNotBlank()) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy MCP client access key")
                }
            })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val serverRunning = local == "Local server running"
            FilledTonalButton(onClick = {
                if (serverRunning) {
                    // Stop must not use startForegroundService: the service may not
                    // promote itself to foreground on this path.
                    runCatching {
                        context.startService(Intent(context, LogMonitorService::class.java)
                            .setAction(LogMonitorService.LOCAL_STOP))
                        hint = "Stopping server…"
                    }.onFailure { hint = "Cannot stop MCP service: " + it.javaClass.simpleName }
                } else {
                    hint = "Starting server…"
                    sendAction(LogMonitorService.LOCAL_START)
                }
            }) {
                Icon(if (serverRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(if (serverRunning) "Stop server" else "Start server")
            }
            OutlinedButton(onClick = { sendAction(LogMonitorService.TUNNEL_RESTART) },
                enabled = serverRunning) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text("Reconnect tunnel")
            }
        }
        TextButton(onClick = { rotating = true }) { Text("Rotate access key") }
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Connect your AI client", style = MaterialTheme.typography.titleSmall)
        Text("Copy the public HTTPS MCP URL into Claude or ChatGPT's custom MCP connector settings. Use the separate client key as Bearer authentication when supported. You must approve the connector in that service. Some accounts or clients require OAuth rather than a static token.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://claude.ai")))
                }
            }) { Text("Open Claude") }
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://chatgpt.com")))
                }
            }) { Text("Open ChatGPT") }
        }
        Text("No separate Docker gateway or continuous log upload is required. Logs stay on the phone and are returned only when an authenticated MCP tool is called.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LogsPage(modifier: Modifier) {
    LogcatScreen(modifier)
}
