package dev.n2k02.mcplogger.adb

import kotlinx.coroutines.CancellationException

/** Shizuku-only shell command facade for MCP tools and Logcat. */
object AdbTransport {
    val isConnected: Boolean get() = ShizukuHostAdapter.current()?.state?.value?.connected == true

    suspend fun exec(command: String, timeoutMs: Long = 15_000L): Result<String> =
        try {
            val session = checkNotNull(ShizukuHostAdapter.current()) {
                "Shizuku shell is not initialized"
            }
            Result.success(session.execVerified(command, timeoutMs))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    suspend fun disconnect() { ShizukuHostAdapter.current()?.disconnectIdle() }
}
