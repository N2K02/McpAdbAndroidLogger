package dev.n2k02.mcplogger

internal data class HomeBadge(val label: String, val tone: Tone)

internal object HomeBadgePolicy {
    fun logs(reading: Boolean, buffered: Boolean): HomeBadge = when {
        reading -> HomeBadge("Reading", Tone.BUSY)
        buffered -> HomeBadge("Ready", Tone.GOOD)
        else -> HomeBadge("Idle", Tone.IDLE)
    }

    fun adb(verified: Boolean, reading: Boolean): HomeBadge = when {
        !verified -> HomeBadge("Offline", Tone.BAD)
        reading -> HomeBadge("Reading", Tone.BUSY)
        else -> HomeBadge("Connected", Tone.GOOD)
    }

    fun mcp(tunnel: String, mcpReading: Boolean): HomeBadge {
        if (mcpReading) return HomeBadge("Reading", Tone.BUSY)
        return when (tunnelTone(tunnel)) {
            Tone.GOOD -> HomeBadge("Online", Tone.GOOD)
            Tone.BUSY -> HomeBadge("Connecting", Tone.BUSY)
            Tone.BAD -> HomeBadge("Offline", Tone.BAD)
            Tone.IDLE -> HomeBadge("Stopped", Tone.IDLE)
        }
    }

    fun performance(reading: Boolean) =
        if (reading) HomeBadge("Running", Tone.BUSY)
        else HomeBadge("On demand", Tone.IDLE)
}
