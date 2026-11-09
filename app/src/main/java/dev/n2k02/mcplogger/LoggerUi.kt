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
import dev.n2k02.mcplogger.core.StableMcpRelay
import dev.n2k02.mcplogger.core.LogMonitorService
import dev.n2k02.mcplogger.core.McpBootSettings
import dev.n2k02.mcplogger.core.MonitorState
import dev.n2k02.updater.N2K02UpdatePage
import dev.n2k02.updater.N2K02UpdateBadge
import dev.n2k02.updater.N2K02UpdateBadgedIcon
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggerApp(onQuit: () -> Unit) {
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
    val tunnel by MonitorState.tunnel.collectAsState()
    val foregroundServiceActive by MonitorState.foregroundServiceActive.collectAsState()
    val tunnelStatusTone = if (foregroundServiceActive) tunnelTone(tunnel) else Tone.BAD
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
                                Page.MCP_AUDITS -> Icons.Rounded.ManageSearch
                                Page.LOGS -> Icons.Rounded.List
                                Page.UPDATES -> Icons.Rounded.SystemUpdate
                            }, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(page.title, style = MaterialTheme.typography.titleMedium)
                    }
                },
                navigationIcon = if (page !in listOf(Page.HOME, Page.MCP_AUDITS, Page.LOGS, Page.SETTINGS)) {
                    { IconButton(onClick = { navigateBack() }) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    } }
                } else { {} },
                actions = {
                    IconButton(onClick = { navigateTo(Page.BRIDGE) }) {
                        Icon(Icons.Rounded.Cloud,
                            contentDescription = if (foregroundServiceActive) "MCP tunnel status: $tunnel" else "MCP foreground service stopped",
                            tint = tunnelStatusTone.colors().dot,
                            modifier = Modifier.size(22.dp))
                    }
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
                NavigationBarItem(selected = LoggerNavigation(page, subpageParent).auditsSelected,
                    onClick = { navigateTo(Page.MCP_AUDITS) },
                    icon = { Icon(Icons.Rounded.ManageSearch, null) },
                    label = { Text("Audits") })
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
            Page.HOME -> HomePage(Modifier.padding(padding),
                onLogs = { navigateTo(Page.LOGS) },
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
            Page.MCP_AUDITS -> McpAuditsScreen(Modifier.padding(padding))
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
private fun HomePage(modifier: Modifier, onLogs: () -> Unit,
    onAdb: () -> Unit, onMcp: () -> Unit) {
    HomeDashboard(modifier, onLogs, onAdb, onMcp)
}

@Composable
private fun AdbPage(modifier: Modifier) {
    val context = LocalContext.current
    val adb = remember(context.applicationContext) {
        dev.n2k02.mcplogger.adb.SharedAdbHostAdapter.core(context)
    }
    // Keep the MCP pairing helper: the library's generic Accessibility service
    // is not a substitute for this app's already-declared Android service.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val accessibilityEnabled = remember(resumeTick) {
        dev.n2k02.mcplogger.adb.PairingAccessibilityService.enabled(context)
    }
    Column(modifier.verticalScroll(rememberScrollState())) {
        dev.n2k02.adb.core.AdbConnectionSettingsPage(
            adb = adb,
            accessibilityEnabled = accessibilityEnabled,
            onOpenAccessibility = {
                runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            },
        )
    }
}

@Composable
private fun BridgePage(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val local by MonitorState.localMcp.collectAsState()
    val tunnel by MonitorState.tunnel.collectAsState()
    var startMcpOnBoot by remember { mutableStateOf(McpBootSettings.startOnBoot(context)) }
    val publicUrl by MonitorState.publicMcpUrl.collectAsState()
    val clientToken = BridgeClient.clientTokenState(context).collectAsState().value.orEmpty()
    val agents by dev.n2k02.mcplogger.core.LocalMcpOAuth.connected.collectAsState()
    var permanentUrl by remember { mutableStateOf(StableMcpRelay.savedUrl(context)) }
    var editingPermanentUrl by remember { mutableStateOf(StableMcpRelay.publicMcpUrl(context) == null) }
    // Do not put the device secret in Compose saved state bundles.
    var permanentKey by remember { mutableStateOf(StableMcpRelay.savedKey(context)) }
    var revokeAgent by remember { mutableStateOf<dev.n2k02.mcplogger.core.LocalMcpOAuth.ConnectedClient?>(null) }
    LaunchedEffect(clientToken, publicUrl) {
        dev.n2k02.mcplogger.core.LocalMcpOAuth.refreshState(context)
    }
    var hint by remember { mutableStateOf("") }
    var resetOauth by remember { mutableStateOf(false) }

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
            ") · Client ID …" + requestedRevoke.id.takeLast(8) +
            " will lose access immediately. It can only reconnect by getting your approval again.") },
        confirmButton = {
            TextButton(onClick = {
                dev.n2k02.mcplogger.core.LocalMcpOAuth.revokeClient(context, requestedRevoke.id)
                revokeAgent = null
                hint = "Access revoked for " + requestedRevoke.name
            }) { Text("Revoke") }
        },
        dismissButton = { TextButton(onClick = { revokeAgent = null }) { Text("Cancel") } },
    )

    if (resetOauth) AlertDialog(
        onDismissRequest = { resetOauth = false },
        title = { Text("Reset all OAuth authorizations?") },
        text = { Text("Revokes all AI agent access tokens, clears pending approvals and repairs inaccessible OAuth state. Your public MCP URL and shared access key stay unchanged. Reconnect each agent afterward.") },
        confirmButton = {
            TextButton(onClick = {
                resetOauth = false
                runCatching {
                    dev.n2k02.mcplogger.core.LocalMcpOAuth.resetAuthorizations(context)
                    hint = "OAuth authorizations reset. Reconnect your AI agent."
                }.onFailure { hint = "Could not reset OAuth state: " + it.javaClass.simpleName }
            }) { Text("Reset OAuth") }
        },
        dismissButton = { TextButton(onClick = { resetOauth = false }) { Text("Cancel") } },
    )

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val stableUrl = StableMcpRelay.publicMcpUrl(context)
        val publicStatus = when {
            stableUrl == null -> "Setup needed"
            tunnel == "Public MCP connected" -> "Online"
            tunnel.contains("reconnect", true) || tunnel.contains("connect", true) ||
                tunnel.contains("starting", true) -> "Connecting"
            else -> "Offline"
        }
        CardHeader(Icons.Rounded.Cloud, "Public MCP",
            if (stableUrl == null) Tone.IDLE else tunnelTone(tunnel),
            publicStatus, subtitle = when {
                stableUrl == null -> "Permanent HTTPS endpoint not configured"
                tunnel == "Public MCP connected" -> "Permanent endpoint connected"
                else -> "Permanent endpoint: $tunnel"
            })
        Text("One permanent URL for ChatGPT. The Android app automatically maintains the underlying tunnel after network changes and restarts.",
            style = MaterialTheme.typography.bodySmall)
        Text("Phone-hosted server: $local", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Start MCP on boot", style = MaterialTheme.typography.bodyLarge)
                Text("Start the local server and tunnel after the phone restarts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = startMcpOnBoot, onCheckedChange = { enabled ->
                startMcpOnBoot = enabled
                McpBootSettings.setStartOnBoot(context, enabled)
            })
        }
        OutlinedTextField(
            value = if (editingPermanentUrl) permanentUrl else stableUrl.orEmpty(),
            onValueChange = { if (editingPermanentUrl) permanentUrl = it },
            readOnly = !editingPermanentUrl,
            label = { Text("Permanent HTTPS MCP URL") },
            placeholder = { Text("https://your-worker.workers.dev/mcp") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            trailingIcon = {
                if (!editingPermanentUrl) IconButton(
                    onClick = { copy("Permanent MCP URL", stableUrl.orEmpty()) },
                    enabled = !stableUrl.isNullOrBlank()
                ) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy permanent MCP URL") }
            }
        )
        if (editingPermanentUrl) {
            Text("Initial setup only: deploy the bundled Cloudflare Worker, then enter its URL and private registration secret. GitHub Pages cannot host a live MCP endpoint.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = permanentKey, onValueChange = { permanentKey = it },
                label = { Text("Worker registration secret") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    if (permanentUrl.isNotBlank() && permanentKey.isNotBlank() &&
                        StableMcpRelay.save(context, permanentUrl, permanentKey)) {
                        editingPermanentUrl = false
                        hint = "Permanent endpoint saved. Connecting automatically…"
                        sendAction(LogMonitorService.TUNNEL_RESTART)
                    } else {
                        hint = "Enter a deployed *.workers.dev/mcp URL and its matching 32+ character secret."
                    }
                }) { Text("Save and connect") }
                if (stableUrl != null) TextButton(onClick = {
                    permanentUrl = stableUrl
                    permanentKey = StableMcpRelay.savedKey(context)
                    editingPermanentUrl = false
                }) { Text("Cancel") }
            }
        } else {
            TextButton(onClick = {
                permanentUrl = StableMcpRelay.savedUrl(context)
                permanentKey = StableMcpRelay.savedKey(context)
                editingPermanentUrl = true
            }) { Text("Change permanent endpoint") }
        }
        OutlinedTextField(value = clientToken,
            onValueChange = {}, readOnly = true,
            label = { Text("MCP client access key") }, singleLine = true,
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
        Text("Reconnecting the tunnel, restarting the app, or changing networks does not rotate your MCP URL or client access key.",
            style = MaterialTheme.typography.bodySmall)
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardHeader(Icons.Rounded.Link, "Connect your AI client", Tone.BUSY, null)
        Text("One connection flow for Claude, ChatGPT and any compatible MCP agent.",
            style = MaterialTheme.typography.bodyMedium)
        Text("1. Once the permanent endpoint shows Online, copy the single HTTPS MCP URL above.",
            style = MaterialTheme.typography.bodySmall)
        Text("2. In your AI agent's custom MCP connector, paste that URL. Select OAuth and automatic client registration (DCR) if requested. No custom headers are needed.",
            style = MaterialTheme.typography.bodySmall)
        Text("3. The agent opens this phone-hosted authorization page. Check the requesting agent name and redirect domain before approving.",
            style = MaterialTheme.typography.bodySmall)
        Text("4. Enter the existing MCP client access key shown above on the authorization page, then tap Approve access.",
            style = MaterialTheme.typography.bodySmall)
        Text("5. The agent connects with its own access token. Manage or revoke its access below without affecting other agents.",
            style = MaterialTheme.typography.bodySmall)
        Text("The original key stays private. Each new agent repeats the same flow. It must support OAuth with dynamic client registration; some providers require other registration methods.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("Added dates identify registrations. Exact matching agent registrations " +
            "are automatically cleaned up after a replacement is successfully authorized.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        val dateLabel = if (agent.authorizedAtMillis > 0L)
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(agent.authorizedAtMillis))
                        else "Unavailable (authorized before update)"
                        Text("Added: " + dateLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Client ID: …" + agent.id.takeLast(8),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (agent.duplicateCount > 1)
                            Text("Matching registrations: " + agent.duplicateCount +
                                " · revoke older entries individually",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { revokeAgent = agent }) { Text("Revoke") }
                }
            }
        }
        OutlinedButton(onClick = { resetOauth = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Reset OAuth authorizations")
        }
        Text("Use Reset only if OAuth approvals are stuck or storage is damaged. Existing OAuth clients must reconnect; the MCP URL and shared key remain intact.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
