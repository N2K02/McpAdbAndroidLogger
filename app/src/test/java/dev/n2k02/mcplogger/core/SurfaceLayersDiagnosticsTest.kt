package dev.n2k02.mcplogger.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SurfaceLayersDiagnosticsTest {
    private val catalog = """
        package:com.android.chrome
        package:com.android.systemui
        package:com.example.overlay
        package:com.example.other
    """.trimIndent()

    @Test fun layerSummaryGroupsInstalledPackagesWithoutLeakingWindowTitles() {
        val input = """
            Total layers: 6
            SurfaceView[com.android.chrome/com.android.chrome.Main] - private banking password
            com.android.chrome/com.android.chrome.MainActivity#0
            com.android.systemui/com.android.systemui.statusbar.StatusBar
            popup com.example.overlay/.FloatingView OTP=876543
            unread SMS from Jane - com.unknown.private.app
            unknown wallpaper layer
        """.trimIndent()
        val result = SurfaceLayersDiagnostics.parseLayers(input, catalog)
        assertEquals("ok", result.getString("status"))
        assertEquals(6, result.getInt("observed_layer_count"))
        assertEquals(3, result.getInt("attributed_package_count"))
        assertEquals(2, result.getInt("unattributed_layer_count"))
        val rows = result.getJSONArray("packages")
        assertEquals(3, rows.length())
        assertEquals("com.android.chrome", rows.getJSONObject(0).getString("package_name"))
        assertEquals(2, rows.getJSONObject(0).getInt("layer_count"))
        assertEquals("com.android.systemui", rows.getJSONObject(1).getString("package_name"))
        assertEquals("com.example.overlay", rows.getJSONObject(2).getString("package_name"))
        assertFalse(result.toString().contains("private banking password"))
        assertFalse(result.toString().contains("OTP="))
        assertFalse(result.toString().contains("Jane"))
        assertFalse(result.toString().contains("com.unknown.private.app"))
    }

    @Test fun prefixesAndUnverifiedPackagesAreNeverMisattributed() {
        val result = SurfaceLayersDiagnostics.parseLayers("""
            com.android.chromex/layer
            com.android.chrome.sandboxed/layer
            com.android.chrome/layer
            com.example.other/com.android.chrome overlay
        """.trimIndent(), catalog)
        val rows = result.getJSONArray("packages")
        assertEquals(2, rows.length())
        assertEquals("com.android.chrome", rows.getJSONObject(0).getString("package_name"))
        assertEquals(2, rows.getJSONObject(0).getInt("layer_count"))
        assertEquals("com.example.other", rows.getJSONObject(1).getString("package_name"))
        assertEquals(2, result.getInt("unattributed_layer_count"))
    }

    @Test fun permissionErrorsAndMissingCatalogFailClosedAndNeverExposeRawData() {
        val forbidden = SurfaceLayersDiagnostics.parseLayers(
            "Permission Denial: can't dump SurfaceFlinger from uid 2000", catalog)
        assertEquals("permission_denied", forbidden.getString("status"))
        assertFalse(forbidden.toString().contains("uid 2000"))
        val missing = SurfaceLayersDiagnostics.parseLayers(
            "com.android.chrome Window SECRET", "Permission denied")
        assertEquals("package_catalog_unavailable", missing.getString("status"))
        assertFalse(missing.toString().contains("SECRET"))
        assertEquals("unavailable",
            SurfaceLayersDiagnostics.parseLayers("Can't find service: SurfaceFlinger",
                catalog).getString("status"))
        val empty = SurfaceLayersDiagnostics.parseLayers("", catalog)
        assertEquals("unavailable", empty.getString("status"))
        assertEquals("no_layers", SurfaceLayersDiagnostics.parseLayers(
            "Total layers: 0", catalog).getString("status"))
    }

    @Test fun scanAndResponseAreBoundedAndOnlyIncludePackages() {
        val many = (0 until 800).joinToString("\n") {
            "com.android.chrome/layer#$it with SensitiveTitle=private"
        }
        val result = SurfaceLayersDiagnostics.parseLayers(many, catalog, limit = 1)
        assertEquals(600, result.getInt("observed_layer_count"))
        assertTrue(result.getBoolean("limited_input"))
        assertEquals(1, result.getJSONArray("packages").length())
        assertEquals(600, result.getJSONArray("packages")
            .getJSONObject(0).getInt("layer_count"))
        assertFalse(result.toString().contains("SensitiveTitle"))
        val packages = SurfaceLayersDiagnostics.parsePackages(
            "package:com.android.chrome\npackage:invalidname\npackage:com.example.overlay\n" +
                "ignored:com.secret.private")
        assertEquals(setOf("com.android.chrome", "com.example.overlay"), packages)
    }

    @Test fun surfaceToolIsReadOnlyExplicitAndRequiresNoPrivateLayerExports() {
        val root = "app/src/main/java/dev/n2k02/mcplogger/"
        fun source(path: String) = listOf(File(root + path), File("../" + root + path))
            .first { it.isFile }.readText()
        val diag = source("core/SurfaceLayersDiagnostics.kt")
        val server = source("core/LocalMcpServer.kt")
        assertTrue(diag.contains("dumpsys SurfaceFlinger --list"))
        assertTrue(diag.contains("pm list packages"))
        assertTrue(diag.contains("AdbCoordinator.verified.value"))
        assertTrue(diag.contains("lock.tryLock()"))
        assertFalse(diag.contains("ProcessBuilder("))
        assertFalse(diag.contains("Runtime.getRuntime()"))
        assertFalse(diag.contains("dumpsys SurfaceFlinger --timestats -clear"))
        assertTrue(server.contains("SurfaceLayersDiagnostics.layers("))

        val list = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/list","id":1}"""))!!
            .getJSONObject("result").getJSONArray("tools")
        assertEquals(25, list.length())
        val surface = (0 until list.length()).map { list.getJSONObject(it) }
            .single { it.getString("name") == "get_surfaceflinger_layers" }
        assertTrue(surface.getString("description").contains("not per-app CPU"))
        assertEquals("integer", surface.getJSONObject("inputSchema")
            .getJSONObject("properties").getJSONObject("limit").getString("type"))
        val result = LocalMcpProtocol.execute(JSONObject(
            """{"jsonrpc":"2.0","method":"tools/call","id":2,
               "params":{"name":"get_surfaceflinger_layers","arguments":{"limit":8}}}"""),
            performanceReader = { name, args ->
                assertEquals("get_surfaceflinger_layers", name)
                assertEquals(8, args.getInt("limit"))
                JSONObject().put("status", "mocked")
            })!!
        assertEquals("mocked", JSONObject(result.getJSONObject("result")
            .getJSONArray("content").getJSONObject(0).getString("text"))
            .getString("status"))
    }
}
