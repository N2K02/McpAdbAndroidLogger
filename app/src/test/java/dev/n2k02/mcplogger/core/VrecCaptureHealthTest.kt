package dev.n2k02.mcplogger.core
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.async

class VrecCaptureHealthTest {
    private val live = "VREC_LIVE_1 | at=10000 | active=1 | mic=SILENCED;source=MIC;attempts=2;reason=POLICY_SILENCED;events=private | PCM diagnostic: frames=48000 L rms=1000.0 silence=0.0% clip=0.0%; R rms=0.0 silence=100.0% clip=0.0% | RIGHT_CHANNEL_QUIET"
    @Test fun concurrentSystemSnapshotsQueueAndReleaseAfterFailure() = kotlinx.coroutines.runBlocking {
        val firstEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var secondEntered = false
        val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            OnDemandSystemDiagnostics.serialized {
                firstEntered.complete(Unit)
                release.await()
            }
        }
        firstEntered.await()
        val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            OnDemandSystemDiagnostics.serialized { secondEntered = true }
        }
        assertFalse(secondEntered)
        release.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered)
        try { OnDemandSystemDiagnostics.serialized { error("test failure") } }
        catch (_: IllegalStateException) { }
        assertEquals("released", OnDemandSystemDiagnostics.serialized { "released" })
    }

    @Test fun freshMicAndPcmEvidenceAreAvailableWithoutLogcat() {
        val health = VrecCaptureHealth.parse(live, 15000)
        assertEquals("fresh_snapshot", health.getString("status"))
        assertEquals("active_at_sample", health.getString("recording_state"))
        assertEquals("SILENCED", health.getString("mic_state"))
        assertEquals(0.0, health.getDouble("microphone_rms"), 0.0)
        assertEquals(100.0, health.getDouble("microphone_silence_percent"), 0.0)
        assertEquals("POLICY_SILENCED", health.getString("mic_reason"))
        assertFalse(health.toString().contains("private"))
        assertFalse(health.has("events"))
    }
    @Test fun staleOrMalformedFilesNeverClaimAnActiveRecording() {
        for (raw in listOf("", "garbage", live.replace("active=1", "active=bad"), "x".repeat(1025), live.replace("mic=SILENCED", "mic=bogus"))) {
            assertEquals("unknown", VrecCaptureHealth.parse(raw, 15000).getString("recording_state"))
        }
        assertEquals("stale", VrecCaptureHealth.parse(live, 30001).getString("status"))
        assertEquals("stale", VrecCaptureHealth.parse(live, 9999).getString("status"))
        assertEquals("stopped_at_sample", VrecCaptureHealth.parse(live.replace("active=1", "active=0"), 15000).getString("recording_state"))
    }
    @Test fun optionalUntrustedValuesAndInvalidMetricsAreNotExported() {
        val result = VrecCaptureHealth.parse(live.replace("reason=POLICY_SILENCED", "reason=secret").replace("attempts=2", "attempts=999").replace("rms=1000.0", "rms=999999.0"), 15000)
        assertFalse(result.has("mic_reason"))
        assertFalse(result.has("mic_attempts"))
        assertFalse(result.has("playback_rms"))
    }
}
