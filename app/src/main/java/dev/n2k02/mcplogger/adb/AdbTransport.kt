package dev.n2k02.mcplogger.adb

import android.content.Context
import kotlinx.coroutines.CancellationException

/** Legacy call-site facade. Shell commands run exclusively in Shizuku's UID-2000 user service. */
object AdbTransport {
    const val LOOPBACK = "127.0.0.1"
    const val LEGACY_TCP_PORT = 5555

    val isConnected: Boolean get() = SharedAdbHostAdapter.current()?.state?.value?.connected == true
    val lastKnownConnected: Boolean get() = isConnected

    suspend fun connect(context: Context, host: String, port: Int): Result<Unit> =
        checked {
            require(host == LOOPBACK && port == LEGACY_TCP_PORT)
            check(SharedAdbHostAdapter.core(context).retry(auto = true)) {
                "LocalADB Shizuku authorization required"
            }
        }

    suspend fun pairLocal(context: Context, port: Int, code: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Shizuku requires per-app permission, not ADB pairing"))

    suspend fun connectLocalTls(context: Context, port: Int): Result<Unit> =
        checked {
            check(SharedAdbHostAdapter.core(context).retry(auto = true)) {
                "LocalADB Shizuku authorization required"
            }
        }

    suspend fun exec(command: String, timeoutMs: Long = 15_000L): Result<String> =
        checked {
            val core = checkNotNull(SharedAdbHostAdapter.current()) {
                "Shizuku shell is not initialized"
            }
            core.execVerified(command, timeoutMs)
        }

    suspend fun switchToTcpip(port: Int = LEGACY_TCP_PORT): Result<Unit> =
        Result.failure(UnsupportedOperationException("TCP/5555 is not used by Shizuku"))

    suspend fun switchToUsb(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Shizuku does not control ADB TCP modes"))

    suspend fun disconnect() { SharedAdbHostAdapter.current()?.disconnectIdle() }
    suspend fun close() = disconnect()

    private suspend inline fun <T> checked(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
