package dev.n2k02.mcplogger

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.n2k02.mcplogger.adb.AdbCoordinator
import kotlinx.coroutines.launch

/**
 * Initial authorization and manual recovery live on Home, not in the
 * VRec-compatible ADB connection settings submenu.
 */
@Composable
internal fun AdbHomeSetupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val verified by AdbCoordinator.verified.collectAsState()
    val discovery by AdbCoordinator.discoveryState.collectAsState()
    val manualRetry by AdbCoordinator.manualRetryRequired.collectAsState()
    var code by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    if (verified) return

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Connect Android ADB", style = MaterialTheme.typography.titleMedium)
            Text(if (manualRetry) "Android authorization needs manual Retry."
                else discovery.status, style = MaterialTheme.typography.bodySmall)
            Text("Enable Wireless debugging in Developer options, then open Pair device with pairing code. Once authorized, TCP/5555 can reconnect when Wi-Fi is off.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { note = "Cannot open Developer options" }
            }, modifier = Modifier.fillMaxWidth()) { Text("Open Android Developer options") }
            OutlinedButton(onClick = {
                busy = true
                scope.launch {
                    try {
                        val result = AdbCoordinator.connect(context, userInitiated = true)
                        note = if (result.isSuccess) "ADB shell UID 2000 verified."
                            else "ADB unavailable: " + (result.exceptionOrNull()?.message ?: "pairing required")
                    } finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Reconnect / verify ADB") }
            TextButton(onClick = { showManual = !showManual }) {
                Text(if (showManual) "Hide pairing controls" else "Pair manually")
            }
            if (showManual) {
                Text("Pairing port: " + (discovery.pairingPort?.toString() ?: "Searching…"),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Pairing port (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Six-digit Android pairing code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val selectedPort = port.toIntOrNull() ?: discovery.pairingPort
                    if (selectedPort == null) {
                        note = "Open Android's pairing dialog to discover its port."
                    } else {
                        busy = true
                        scope.launch {
                            try {
                                val result = AdbCoordinator.pair(context, selectedPort, code)
                                code = ""
                                note = if (result.isSuccess) {
                                    val connected = AdbCoordinator.connect(context, userInitiated = true)
                                    if (connected.isSuccess) "ADB shell UID 2000 verified."
                                    else "Paired. Open Wireless debugging and tap Reconnect."
                                } else "Pairing rejected. Check the current Android pairing code."
                            } finally { busy = false }
                        }
                    }
                }, enabled = !busy && code.length == 6) { Text("Pair and connect ADB") }
            }
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}
