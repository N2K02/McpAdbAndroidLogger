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
import dev.n2k02.mcplogger.adb.AdbRecoverySettings
import dev.n2k02.mcplogger.adb.AdbRecoveryLifecycle
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
    val verified by AdbCoordinator.verified.collectAsState()
    val authorizationRequired by AdbCoordinator.manualRetryRequired.collectAsState()
    val autoCodeEnabled = dev.n2k02.mcplogger.adb.PairingAccessibilityService.enabled(context)
    var autoPair by remember { mutableStateOf(AdbRecoverySettings.autoPair(context)) }
    var watchdog by remember { mutableStateOf(AdbRecoverySettings.watchdog(context)) }
    var boot by remember { mutableStateOf(AdbRecoverySettings.startOnBoot(context)) }
    var restore by remember { mutableStateOf(AdbRecoverySettings.restoreAfterRestart(context)) }
    var tcp by remember { mutableStateOf(AdbRecoverySettings.tcpMode(context)) }
    var note by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var manualPorts by rememberSaveable { mutableStateOf(false) }
    var pairPort by rememberSaveable { mutableStateOf("") }
    var connectPort by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val tcpOpen by AdbCoordinator.tcpPortOpen.collectAsState()

    @Composable
    fun PreferenceRow(title: String, subtitle: String, checked: Boolean,
                      onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pairing & recovery", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                PreferenceRow("Auto-detect pairing code",
                    "Read the Android pairing dialog with Accessibility.",
                    autoPair) {
                    autoPair = it
                    AdbRecoverySettings.setAutoPair(context, it)
                }
                if (autoPair && !autoCodeEnabled && !verified) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }.onFailure { note = "Cannot open Accessibility settings" }
                    }) { Text("Enable pairing Accessibility helper") }
                }
                HorizontalDivider()
                PreferenceRow("Connection watchdog",
                    "Check connection health and recover automatically when possible.",
                    watchdog) {
                    watchdog = it
                    AdbRecoveryLifecycle.configureWatchdog(context, it)
                }
                PreferenceRow("Start on boot",
                    "Try to restore the connection after restarting and unlocking.",
                    boot) {
                    boot = it
                    AdbRecoverySettings.setStartOnBoot(context, it)
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Connection & security", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                PreferenceRow("Restore after restart",
                    "Restore Wireless debugging after reboot, using saved pairing. " +
                    "Requires Android permission; never overrides a manual off setting.",
                    restore) {
                    restore = it
                    AdbRecoverySettings.setRestoreAfterRestart(context, it)
                    scope.launch {
                        note = when {
                            it && verified ->
                                if (AdbRecoverySettings.grantSecureSettings(context))
                                    "Restore after restart is enabled."
                                else "Android refused the permission; restore requires verified ADB."
                            it -> "Restore will request its permission after ADB is verified."
                            verified ->
                                if (AdbRecoverySettings.revokeSecureSettings(context))
                                    "Restore after restart is off."
                                else "Restore off; permission could not be revoked."
                            else -> "Restore after restart is off."
                        }
                    }
                }
                PreferenceRow("Keep ADB without Wi-Fi (TCP/5555)",
                    "Keeps ADB available without Wi-Fi. TCP/5555 may be " +
                    "reachable by devices on your network.",
                    tcp) { enabled ->
                    tcp = enabled
                    AdbRecoverySettings.setTcpMode(context, enabled)
                    if (!enabled && tcpOpen && verified) {
                        working = true
                        scope.launch {
                            try {
                                val result = AdbCoordinator.disableTcp(context)
                                note = result.fold(
                                    { "TCP/5555 closed; pair or reconnect via Wireless debugging." },
                                    { "TCP/5555 may still be open: " + (it.message ?: "unknown") })
                            } finally { working = false }
                        }
                    } else {
                        note = if (enabled) "TCP/5555 enabled for next verified connection."
                            else "Automatic TCP/5555 is disabled."
                    }
                }
                if (tcpOpen && verified) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        working = true
                        scope.launch {
                            try {
                                val result = AdbCoordinator.disableTcp(context)
                                note = result.fold(
                                    { "TCP/5555 disabled." },
                                    { "Could not close TCP/5555: " + (it.message ?: "unknown") })
                            } finally { working = false }
                        }
                    }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                        Text("Disable TCP/5555")
                    }
                }
            }
        }

        // VRec keeps pairing on Home; logger keeps an accessible manual
        // pairing panel here because its Home is a diagnostics-only dashboard.
        if (!verified) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connect Android ADB", style = MaterialTheme.typography.titleMedium)
                    Text(if (authorizationRequired)
                        "Android needs manual authorization. Tap Retry below."
                    else discovery.status, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        }.onFailure { note = "Cannot open Developer options" }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Android Developer options")
                    }
                    Text("Enable Wireless debugging, then choose 'Pair device with pairing code'. " +
                        "If Accessibility is enabled, the code can be read automatically.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide pairing controls" else "Pair manually")
                    }
                    if (expanded || discovery.pairingPort != null) {
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) },
                            label = { Text("Six-digit pairing code") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                        Button(onClick = {
                            working = true
                            scope.launch {
                                try {
                                    val port = pairPort.toIntOrNull() ?: discovery.pairingPort
                                    if (port == null) note = "Open Android pairing dialog to discover its port."
                                    else {
                                        val result = AdbCoordinator.pair(context, port, code)
                                        code = ""
                                        note = if (result.isSuccess) {
                                            val connected = AdbCoordinator.connect(context,
                                                userInitiated = true)
                                            if (connected.isSuccess) "UID 2000 verified."
                                            else "Paired; open Wireless debugging and retry."
                                        } else "Pairing was rejected; enter the current Android code."
                                    }
                                } finally { working = false }
                            }
                        }, enabled = code.length == 6 && !working,
                            modifier = Modifier.fillMaxWidth()) { Text("Pair and connect ADB") }
                    }
                    OutlinedButton(onClick = {
                        working = true
                        scope.launch {
                            try {
                                val result = AdbCoordinator.connect(context,
                                    connectPort.toIntOrNull(), userInitiated = true)
                                note = if (result.isSuccess) "UID 2000 verified."
                                    else "ADB unavailable: " +
                                        (result.exceptionOrNull()?.message ?: "retry pairing")
                            } finally { working = false }
                        }
                    }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                        Text("Reconnect / verify ADB")
                    }
                    TextButton(onClick = { manualPorts = !manualPorts }) {
                        Text(if (manualPorts) "Hide advanced port controls"
                            else "Advanced: manual ports")
                    }
                    if (manualPorts) {
                        OutlinedTextField(pairPort,
                            { pairPort = it.filter(Char::isDigit).take(5) },
                            label = { Text("Pairing port (optional)") }, singleLine = true)
                        OutlinedTextField(connectPort,
                            { connectPort = it.filter(Char::isDigit).take(5) },
                            label = { Text("Connection port (optional)") }, singleLine = true)
                    }
                }
            }
        } else {
            Text("ADB: UID 2000 verified · " + status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BridgePage(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val local by MonitorState.localMcp.collectAsState()
    val tunnel by MonitorState.tunnel.collectAsState()
    val publicUrl by MonitorState.publicMcpUrl.collectAsState()
    val clientToken = BridgeClient.clientTokenState(context).collectAsState().value.orEmpty()
    val agents by dev.n2k02.mcplogger.core.LocalMcpOAuth.connected.collectAsState()
    var revokeAgent by remember { mutableStateOf<dev.n2k02.mcplogger.core.LocalMcpOAuth.ConnectedClient?>(null) }
    LaunchedEffect(clientToken, publicUrl) {
        dev.n2k02.mcplogger.core.LocalMcpOAuth.refreshState(context)
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

    val requestedRevoke = revokeAgent
    if (requestedRevoke != null) AlertDialog(
        onDismissRequest = { revokeAgent = null },
        title = { Text("Revoke this agent?") },
        text = { Text(requestedRevoke.name + " (" + requestedRevoke.domain +
            ") will lose access immediately. It can only reconnect by getting your approval again.") },
        confirmButton = {
            TextButton(onClick = {
                dev.n2k02.mcplogger.core.LocalMcpOAuth.revokeClient(context, requestedRevoke.id)
                revokeAgent = null
                hint = "Access revoked for " + requestedRevoke.name
            }) { Text("Revoke") }
        },
        dismissButton = { TextButton(onClick = { revokeAgent = null }) { Text("Cancel") } },
    )

    if (rotating) AlertDialog(
        onDismissRequest = { rotating = false },
        title = { Text("Rotate URL and access key?") },
        text = { Text("A new public URL and a new MCP access key are created. The current ones stop working immediately, so update both in Claude and ChatGPT.") },
        confirmButton = { TextButton(onClick = {
            rotating = false
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(context,
                    Intent(context, LogMonitorService::class.java)
                        .setAction(LogMonitorService.TUNNEL_ROTATE))
                hint = "Rotating URL and key…"
            }.onFailure { hint = "Cannot rotate: " + it.javaClass.simpleName }
        }) { Text("Rotate") } },
        dismissButton = { TextButton(onClick = { rotating = false }) { Text("Cancel") } },
    )

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardHeader(Icons.Rounded.PhoneAndroid, "Phone-hosted MCP", localMcpTone(local),
            if (localMcpTone(local) == Tone.GOOD) "Running" else local.ifBlank { "Stopped" },
            subtitle = "Local server on this phone")
        Text("Only read-only, redacted Logcat diagnostics are accessible with your private token.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = localUrl, onValueChange = {}, readOnly = true,
            label = { Text("Local MCP URL (other apps on this phone)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { copy("Local MCP URL", localUrl) }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy local MCP URL")
                }
            })
        }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardHeader(Icons.Rounded.Cloud, "Public MCP", tunnelTone(tunnel),
            when (tunnelTone(tunnel)) {
                Tone.GOOD -> "Online"; Tone.BUSY -> "Connecting"
                Tone.BAD -> "Offline"; Tone.IDLE -> "Stopped"
            }, subtitle = "Quick Tunnel: $tunnel")
        if (publicUrl.isBlank()) {
            Text("A public URL appears here once Cloudflare confirms the tunnel. It is saved and reused; it requires the phone to be online.",
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
            onValueChange = { BridgeClient.saveClientToken(context, it) }, label = { Text("MCP client access key") }, singleLine = true,
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
        TextButton(onClick = { rotating = true }) { Text("Rotate URL and access key") }
        Text("The URL and key are saved and stay the same across reconnects and restarts. Rotate only when you need new ones.",
            style = MaterialTheme.typography.bodySmall)
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardHeader(Icons.Rounded.Link, "Connect your AI client", Tone.BUSY, null)
        Text("For Claude, ChatGPT or any OAuth-compatible agent: add this public HTTPS MCP URL. Choose OAuth / automatic registration. When its authorization page opens, enter your existing MCP client access key here on your phone, then approve. Each agent gets its own revocable OAuth tokens; no custom headers are needed.",
            style = MaterialTheme.typography.bodySmall)
        Text("Only approve agents whose callback domains you recognize. Your original key is never shared with an agent.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://claude.ai/new#customize/connectors/yours")))
                }
            }) { Text("Open Claude") }
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://chatgpt.com/plugins")))
                }
            }) { Text("Open ChatGPT") }
        }
        HorizontalDivider()
        Text("Authorized MCP agents", style = MaterialTheme.typography.titleSmall)
        if (agents.isEmpty()) {
            Text("No agents connected by OAuth yet.",
                style = MaterialTheme.typography.bodySmall)
        } else {
            agents.forEach { agent ->
                Row(modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(agent.name, style = MaterialTheme.typography.bodyMedium)
                        Text(agent.domain, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { revokeAgent = agent }) { Text("Revoke") }
                }
            }
        }
        Text("No separate Docker gateway or continuous log upload is required. Logs stay on the phone and are returned only when an authenticated MCP tool is called.",
            style = MaterialTheme.typography.bodySmall)
        }
        }
    }
}

@Composable
private fun LogsPage(modifier: Modifier) {
    LogcatScreen(modifier)
}
