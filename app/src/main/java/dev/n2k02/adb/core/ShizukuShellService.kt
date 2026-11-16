package dev.n2k02.adb.core

import android.os.Binder
import android.os.Parcel
import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Code executes inside the authorized Shizuku user-service process (shell UID 2000). */
class ShizukuShellService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != TRANSACTION_EXEC) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        check(Process.myUid() == Process.SHELL_UID) { "Non-shell Shizuku user service" }
        val command = requireNotNull(data.readString())
        require(command.length in 1..MAX_COMMAND_CHARS) { "Command rejected" }
        val timeout = data.readLong().coerceIn(1_000L, 120_000L)
        val output = execute(command, timeout)
        reply?.writeNoException()
        reply?.writeString(output)
        return true
    }

    private fun execute(command: String, timeout: Long): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true).start()
        process.outputStream.close()
        val bytes = ByteArrayOutputStream()
        val drain = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        synchronized(bytes) {
                            if (bytes.size() < MAX_OUTPUT_BYTES)
                                bytes.write(buffer, 0, minOf(length, MAX_OUTPUT_BYTES - bytes.size()))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            check(process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                "Shizuku command timed out"
            }
            drain.join(1_000L)
            return synchronized(bytes) { bytes.toString(Charsets.UTF_8.name()) }
        } finally {
            process.destroyForcibly()
            drain.interrupt()
        }
    }

    companion object {
        const val DESCRIPTOR = "dev.n2k02.adb.core.ShizukuShellService"
        const val TRANSACTION_EXEC = FIRST_CALL_TRANSACTION + 1
        const val MAX_COMMAND_CHARS = 16384
        const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
    }
}
