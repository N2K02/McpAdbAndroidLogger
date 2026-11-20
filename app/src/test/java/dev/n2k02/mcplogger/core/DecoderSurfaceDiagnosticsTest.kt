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

    @Test fun recognizesAndroidWebViewNestedSandboxRenderer() {
        val top = """
            PID %CPU RES NAME
            5949 109.0 426M com.aayoutube.app
            5983 37.5 531M com.aayoutube.app:com.google.android.webview:sandboxed_process0:org.chromium.content.app.Sandboxed
            6001 8.0 22M com.aayoutube.app:background
            6002 44.0 80M com.aayoutube.app.other
        """.trimIndent()
        val result = DecoderSurfaceDiagnostics.parse("com.aayoutube.app", top, "")
        assertEquals(109.0, result.getJSONObject("main_app_cpu").getDouble("cpu_percent_sum"), 0.01)
        assertEquals(37.5, result.getJSONObject("chromium_renderer_cpu").getDouble("cpu_percent_sum"), 0.01)
        assertEquals(8.0, result.getJSONObject("other_app_process_cpu").getDouble("cpu_percent_sum"), 0.01)
        assertEquals("ok", result.getJSONObject("chromium_renderer_cpu").getString("status"))
    }

    @Test fun countsPidCorrelatedCodecHintsWithoutAttributingOtherApps() {
        val top = """
            PID %CPU RES NAME
            5949 60.0 400M com.aayoutube.app
            5983 45.0 350M com.aayoutube.app:com.google.android.webview:sandboxed_process0:org.chromium.content.app.Sandboxed
        """.trimIndent()
        val logs = """
            11-15 22:40:00.000 5983 6000 I MediaCodec: decoder configured
            11-15 22:40:00.001 5983 6000 I MediaCodec: setOutputSurface
            11-15 22:40:00.002 9999 1111 E MediaCodec: decoder error
            I/MediaCodec( 5949): com.aayoutube.app decoder released
            I/MediaCodec( 7777): com.other.app decoder released
        """.trimIndent()
        val result = DecoderSurfaceDiagnostics.parse("com.aayoutube.app", top, logs)
        val counts = result.getJSONObject("lifecycle_event_counts")
        assertEquals(1, counts.getInt("decoder_created"))
        assertEquals(1, counts.getInt("decoder_released"))
        assertEquals(1, counts.getInt("surface_replaced"))
        assertEquals(0, counts.getInt("decoder_error"))
        assertEquals(2, result.getInt("pid_correlated_lifecycle_lines"))
        assertEquals(1, result.getInt("explicit_package_lifecycle_lines"))
        assertEquals(2, result.getInt("unattributed_codec_log_lines"))
        assertFalse(result.getBoolean("hardware_decoder_confirmed"))
        assertEquals("unknown", result.getString("decoder_state"))
    }

    @Test fun epochFormatPidCorrelationIsAcceptedWithoutHardwareOverclaim() {
        val top = "321 12.5 50M dev.example.player:renderer0"
        val logs = "1794769000.012 10344 321 111 I CCodec: decoder configured"
        val result = DecoderSurfaceDiagnostics.parse(pkg, top, logs)
        assertEquals(1, result.getJSONObject("lifecycle_event_counts").getInt("decoder_created"))
        assertEquals(1, result.getInt("pid_correlated_lifecycle_lines"))
        assertFalse(result.getBoolean("hardware_decoder_confirmed"))
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
