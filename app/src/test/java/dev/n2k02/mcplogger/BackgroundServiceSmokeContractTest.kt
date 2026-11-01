package dev.n2k02.mcplogger

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BackgroundServiceSmokeContractTest {
    @Test fun emulatorMustPutAppInBackgroundAndVerifyMcpRemainsReachable() {
        val path = ".github/scripts/smoke-release-launch.sh"
        val content = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(content.contains("KEYCODE_HOME"))
        assertTrue(content.contains("dumpsys activity services"))
        assertTrue(content.contains("tcp:18765 tcp:8765"))
        assertTrue(content.contains("urlopen(base + \"/health\""))
        assertTrue(content.contains("assert exc.code == 401"))
    }
}
