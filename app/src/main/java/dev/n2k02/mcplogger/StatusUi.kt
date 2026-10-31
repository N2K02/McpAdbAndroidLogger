package dev.n2k02.mcplogger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Status tone mapped onto the n2k02 theme's Material 3 color roles. */
internal enum class Tone { GOOD, BUSY, BAD, IDLE }

internal data class ToneColors(val container: Color, val content: Color, val dot: Color)

@Composable
internal fun Tone.colors(): ToneColors {
    val c = MaterialTheme.colorScheme
    return when (this) {
        Tone.GOOD -> ToneColors(c.primaryContainer, c.onPrimaryContainer, c.primary)
        Tone.BUSY -> ToneColors(c.tertiaryContainer, c.onTertiaryContainer, c.tertiary)
        Tone.BAD -> ToneColors(c.errorContainer, c.onErrorContainer, c.error)
        Tone.IDLE -> ToneColors(c.surfaceVariant, c.onSurfaceVariant, c.outline)
    }
}

internal fun adbTone(status: String) =
    if (status.startsWith("UID 2000 verified")) Tone.GOOD else Tone.BAD

internal fun localMcpTone(status: String) = when {
    status == "Local server running" -> Tone.GOOD
    status == "Stopped" || status.isBlank() -> Tone.IDLE
    else -> Tone.BAD
}

internal fun tunnelTone(status: String) = when {
    status == "Public MCP connected" -> Tone.GOOD
    status == "Stopped" || status.isBlank() -> Tone.IDLE
    status.contains("unavailable", true) || status.contains("disconnected", true) -> Tone.BAD
    else -> Tone.BUSY
}

/** Pill badge: colored dot + short label on a tonal container. */
@Composable
internal fun StatusBadge(label: String, tone: Tone, modifier: Modifier = Modifier) {
    val t = tone.colors()
    Surface(color = t.container, contentColor = t.content, shape = CircleShape,
        modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(t.dot))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Icon on a tonal circle, tinted by tone, used as the leading mark of cards. */
@Composable
internal fun ToneIcon(icon: ImageVector, tone: Tone) {
    val t = tone.colors()
    Surface(color = t.container, contentColor = t.content, shape = CircleShape,
        modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

/** Card header: tonal icon, title, optional subtitle and a status badge. */
@Composable
internal fun CardHeader(icon: ImageVector, title: String, tone: Tone,
    badge: String?, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToneIcon(icon, tone)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (badge != null) StatusBadge(badge, tone)
        trailing()
    }
}
