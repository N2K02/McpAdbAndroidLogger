package dev.n2k02.mcplogger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderSurfaceDiagnosticsTest {
    private val pkg = "dev.example.player"

    @Test fun splitsMainRendererAndOtherProcesses() {
        val top = """
            PID %CPU RES NAME
            101 28.5 100M dev.example.player
            102 71.5 200M dev.example.player:sandboxed_process0
            103 15.0 20M dev.example.player:push
            104 99.0 80M dev.example.player2
            105 8.0 20M com.android.chrome:sandboxed_process1
        """.trimIndent()
        val result = DecoderSurfaceDiagnostics.parse(pkg, top, "")
        assertEquals(28.5, result.getJSONObject("main_app_cpu").getDouble("cpu_percent_sum"), 0.01)
        assertEquals(71.5, result.getJSONObject("chromium_renderer_cpu").getDouble("cpu_percent_sum"), 0.01)
        assertEquals(15.0, result.getJSONObject("other_app_process_cpu").getDouble("cpu_percent_sum"), 0.01)
        assertEquals(1, result.getJSONObject("chromium_renderer_cpu").getJSONArray("processes").length())
        assertFalse(result.getBoolean("hardware_decoder_confirmed"))
    }

    @Test fun countsOnlyExplicitlyAttributableLifecycleHints() {
        val logs = """
            I/MediaCodec( 123): dev.example.player decoder configured
            I/SurfaceView( 123): dev.example.player surfaceCreated
            E/MediaCodec( 222): dev.other.app decoder error
            I/SurfaceView( 123): dev.example.player surfaceDestroyed
            I/MediaCodec( 123): dev.example.player2 decoder released
            I/MediaCodec( 123): dev.example.player decoder released
        """.trimIndent()
        val result = DecoderSurfaceDiagnostics.parse(pkg, "", logs)
        val counts = result.getJSONObject("lifecycle_event_counts")
        assertEquals(1, counts.getInt("decoder_created"))
        assertEquals(1, counts.getInt("decoder_released"))
        assertEquals(1, counts.getInt("surface_created"))
        assertEquals(1, counts.getInt("surface_destroyed"))
        assertEquals(0, counts.getInt("decoder_error"))
        assertEquals("unknown", result.getString("decoder_state"))
    }

    @Test fun rejectsInvalidPackageName() {
        try {
            DecoderSurfaceDiagnostics.parse("dev.example; rm -rf /", "", "")
            throw AssertionError("Expected exact package validation")
        } catch (_: IllegalArgumentException) {
            // Validated before any shell command is constructed.
        }
    }

    @Test fun noObservationDoesNotClaimIdleOrHardwareDecode() {
        val result = DecoderSurfaceDiagnostics.parse(pkg, "", "")
        assertEquals("not_observed", result.getJSONObject("chromium_renderer_cpu").getString("status"))
        assertEquals("unknown", result.getString("surface_state"))
        assertTrue(result.getString("notes").contains("unknown"))
    }
}
