package dev.n2k02.mcplogger.core

/** Human-readable state presented by the ongoing Android foreground notification. */
internal enum class TunnelNotificationState { ONLINE, RECONNECTING, OFFLINE }

internal data class TunnelNotificationPresentation(
    val state: TunnelNotificationState,
    val title: String,
    val message: String,
    val colorArgb: Int,
)

internal object TunnelNotificationPolicy {
    fun fromTunnelStatus(status: String): TunnelNotificationPresentation = when {
        status == "Public MCP connected" -> TunnelNotificationPresentation(
            TunnelNotificationState.ONLINE,
            "MCP tunnel online",
            "Public MCP endpoint is connected",
            0xFF2E7D32.toInt(),
        )
        status.contains("reconnect", true) || status.contains("connect", true) ||
            status.contains("starting", true) || status.contains("request", true) ->
            TunnelNotificationPresentation(
                TunnelNotificationState.RECONNECTING,
                "MCP tunnel reconnecting",
                "Tap to view connection details",
                0xFFF9A825.toInt(),
            )
        else -> TunnelNotificationPresentation(
            TunnelNotificationState.OFFLINE,
            "MCP tunnel offline",
            if (status.isBlank()) "Tap to view connection details"
                else status.take(80),
            0xFFD32F2F.toInt(),
        )
    }
}
