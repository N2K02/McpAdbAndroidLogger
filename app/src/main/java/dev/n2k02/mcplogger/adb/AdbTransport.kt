// Adapted from N2K02/VREC's embedded ADB implementation.
package dev.n2k02.mcplogger.adb

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/**
 * Concrete [AbsAdbConnectionManager] bound to this app's persisted identity.
 *
 * The only interesting line is setApi(). It tells the library which wire
 * protocol the daemon on the other end speaks. We are always talking to the
 * adbd on the same phone the app is running on, so the running SDK_INT is by
 * definition correct - there is no remote device whose version could differ.
 */
private class JemAdbConnectionManager(context: Context) : AbsAdbConnectionManager() {

    private val identity = AdbIdentity.getOrCreate(context)
    // libadb's connection close destroys its key. Always provide a fresh
    // per-connection key object, not the persisted cached identity object.
    private val sessionKey: PrivateKey = AdbSessionKey.copyOf(identity.privateKey)

    init {
        setApi(Build.VERSION.SDK_INT)
        // The library's default is to wait forever - in connect, holding its
        // lock, which every other call (isConnected too) then waits on. A peer
        // that accepts and never answers stalled every revive after it.
        setTimeout(10, TimeUnit.SECONDS)
    }

    override fun getPrivateKey(): PrivateKey = sessionKey

    override fun getCertificate(): Certificate = identity.certificate

    override fun getDeviceName(): String = "McpAdbAndroidLogger"
}

/**
 * Embedded ADB client restricted to localhost TCP/5555 after USB-host setup.
 * Running an ADB client inside VRec does not enable the system daemon.
 */
object AdbTransport {

    private const val TAG = "McpAdbAndroidLogger"

    /** adbd is on this phone. There is no other host this could ever be. */
    const val LOOPBACK = "127.0.0.1"

    /**
     * The legacy fixed ADB-over-TCP port. Only reachable if something has set
     * `service.adb.tcp.port`; this is the sole supported local transport.
     */
    const val LEGACY_TCP_PORT = 5555

    /** `exec:` rather than `shell:` so adbd allocates no PTY. See ShellSession. */
    private const val SHELL_SERVICE = "exec:sh"

    @Volatile
    private var manager: AbsAdbConnectionManager? = null

    /** The one reused shell, and the lock that stops two commands interleaving
     *  on it. A shared stream with a sentinel protocol is only correct while
     *  exactly one caller is writing to it. */
    private var shell: ShellSession? = null
    private val mutex = Mutex()

    private fun manager(context: Context): AbsAdbConnectionManager {
        check(SharedAdbHostAdapter.current() == null) {
            "The shared ADB core owns this app's only transport"
        }
        return manager ?: synchronized(this) {
            manager ?: JemAdbConnectionManager(context.applicationContext).also { manager = it }
        }
    }

    /**
     * The last answer isConnected gave, without asking again - for the main
     * thread, which must not wait on the library's lock: a connect holds it
     * for as long as it takes.
     */
    @Volatile
    var lastKnownConnected: Boolean = false
        get() = SharedAdbHostAdapter.current()?.state?.value?.connected ?: field
        private set

    val isConnected: Boolean
        get() = SharedAdbHostAdapter.current()?.state?.value?.connected ?: try {
            (manager?.isConnected == true).also { lastKnownConnected = it }
        } catch (t: Throwable) {
            lastKnownConnected = false
            false
        }

    /**
     * Connect to an explicitly known port.
     *
     * libadb's connect() returns false for TWO different things - the attempt
     * failed, and "it has already been made" - which its own Javadoc says
     * outright. Treating that boolean as success/failure reports a perfectly
     * good connection as broken, and the symptom is not obvious: connect says
     * it failed, everything afterwards works or does not depending on whether
     * anything checks. isConnected() is what actually settles it.
     */
    suspend fun connect(context: Context, host: String, port: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(host == LOOPBACK && port == LEGACY_TCP_PORT) {
                    "Only USB-bootstrapped localhost ADB TCP/5555 is supported"
                }
                val mgr = manager(context)
                if (mgr.isConnected) {
                    lastKnownConnected = true
                    return@runCatching
                }
                val ok = mgr.connect(host, port)
                check(ok || mgr.isConnected) { "connect refused by adbd at $host:$port" }
                lastKnownConnected = mgr.isConnected
                Log.i(TAG, "connect: established")
            }
        }

    /** Pair only with this phone's Android TLS pairing endpoint.
     * The six-digit code is supplied explicitly by the user and is never logged. */
    suspend fun pairLocal(context: Context, port: Int, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(port in 1..65535 && code.matches(Regex("[0-9]{6}")))
                val mgr = manager(context)
                check(mgr.pair(LOOPBACK, port, code)) { "Android rejected pairing code" }
                Unit
            }
        }

    /** Dynamic TLS listener published by this phone's Wireless debugging settings.
     * No arbitrary IP/device addresses are accepted. */
    suspend fun connectLocalTls(context: Context, port: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(port in 1..65535)
                val mgr = manager(context)
                if (!mgr.isConnected) {
                    check(mgr.connect(LOOPBACK, port) || mgr.isConnected) {
                        "Android TLS debugging connection not authorized"
                    }
                }
                lastKnownConnected = mgr.isConnected
                Unit
            }
        }

    /**
     * Run one command and return everything it wrote.
     *
     * Deliberately uses the one-shot `shell:<cmd>` form rather than opening an
     * interactive `shell:` and writing to it. One-shot means adbd closes the
     * stream when the command finishes, so end-of-output is EOF and not a
     * guess. The interactive form never signals completion, which is fine for a
     * terminal and useless for "run this and tell me what it said".
     *
     * The destination string is built directly instead of going through
     * LocalServices.getDestination(SHELL, ...), which double-quotes any
     * argument containing a space - turning `echo hi` into the single command
     * `"echo hi"`.
     *
     * Note this is the LEGACY shell protocol: stdout and stderr are merged and
     * there is no exit status. Adequate for identification and diagnostics.
     */
    suspend fun exec(command: String, timeoutMs: Long = 15_000): Result<String> {
        // The only runtime transport after cutover is the shared library.
        // Every shell command receives a fresh UID 2000 check under its mutex.
        SharedAdbHostAdapter.current()?.let { core ->
            return try {
                Result.success(core.execVerified(command, timeoutMs))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }
        return withContext(Dispatchers.IO) {
            // isConnected, not merely "a manager object exists". Opening a
            // stream on a manager whose connection has died does not throw - it
            // hands back a stream that yields nothing, so every command
            // silently "succeeds" with no output.
            val mgr = manager
            if (mgr == null || !mgr.isConnected) {
                return@withContext Result.failure(IllegalStateException("ADB is not connected"))
            }

            mutex.withLock {
                runCatching {
                    // Watching from before the stream is opened: opening one
                    // can hang too, and then only closing the whole
                    // connection lets it go.
                    var session: ShellSession? = shell
                    // Cancellation must not cancel the watchdog before a native
                    // readLine/openStream unblocks; its deadline owns teardown.
                    val watchdog = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        kotlinx.coroutines.delay(timeoutMs)
                        // The command's first word only: the daemon's launch
                        // line carries its token.
                        Log.w(TAG, "exec: '${command.substringBefore(' ')} ...' timed out after ${timeoutMs}ms")
                        // Timeout means the shell protocol is no longer trustworthy.
                        // Tear down the entire ADB socket; next connect must create a
                        // fresh session key and verify UID 2000 before issuing commands.
                        runCatching { session?.close() }
                        runCatching { mgr.disconnect() }
                        synchronized(this@AdbTransport) {
                            // A timed-out old command must not reset a newly verified session.
                            if (manager === mgr) {
                                manager = null
                                lastKnownConnected = false
                                shell = null
                            } else if (shell === session) shell = null
                        }
                    }
                    try {
                        // An openStream failure must reach the same catch/finally
                        // as a broken read, otherwise the independent watchdog
                        // can outlive a failed command and kill a fresh session.
                        session = session ?: ShellSession(mgr.openStream(SHELL_SERVICE)).also { shell = it }
                        session!!.run(command)
                    } catch (t: Throwable) {
                        // A desynchronised session stays desynchronised, and a
                        // half-read one would mis-attribute the next command's
                        // output. Drop it; the next call opens a fresh one.
                        runCatching { session?.close() }
                        synchronized(this@AdbTransport) {
                            if (manager === mgr) {
                                manager = null
                                lastKnownConnected = false
                                shell = null
                            } else if (shell === session) shell = null
                        }
                        // Never reuse a poisoned shell manager after EOF.
                        // A later reconnect must verify shell UID 2000 again.
                        runCatching { mgr.disconnect() }
                        throw t
                    } finally {
                        watchdog.cancel()
                    }
                }
            }
        }
    }

    /**
     * ONE shell stream, reused for every command, because opening one per
     * command destroys the connection after exactly eight.
     *
     * WHY EIGHT
     *
     * libadb never acknowledges the peer's stream teardown. AdbStream.close()
     * begins "if (mIsClosed) return", and mIsClosed is exactly what the
     * connection thread sets when adbd's CLSE arrives - so for any stream the
     * remote closed first, which is every one-shot command, close() returns
     * early and the reciprocal CLSE is never sent. adbd is left holding
     * half-open sockets, and refuses new ones once it has collected enough.
     * Measured: commands 1-8 fine, 9 onwards "Stream closed." for good.
     * Retrying cannot help, because nothing about it is transient.
     *
     * WHY exec: AND NOT shell:
     *
     * A persistent shell needs clean I/O, and `shell:` with no command makes
     * adbd allocate a PTY. A PTY echoes back everything written to it and
     * prints a prompt, so output arrives as "HNBKQ:/ $ id" interleaved with
     * real results, and the sentinel line never matches because it turns up
     * wearing a prompt. Turning that off from inside with `stty -echo` only
     * desynchronised things further. The `exec:` service never allocates a PTY,
     * which makes `exec:sh` a persistent shell with clean pipes.
     *
     * A persistent shell never signals end-of-output, so a sentinel marks it:
     * write the command, then echo a marker, and read until the marker comes
     * back. It is random per session so no command's own output can collide.
     */
    private class ShellSession(private val stream: AdbStream) {
        @Volatile private var running = false
        @Volatile private var closing = false
        private val marker = "__mcp_logger_" + java.util.UUID.randomUUID().toString().take(12) + "__"
        private val out = stream.openOutputStream()
        private val reader = stream.openInputStream().bufferedReader()

        init {
            // Turn the terminal echo off ONCE, outside the command protocol.
            //
            // adbd hands back a PTY even for `exec:sh`, and a PTY echoes back
            // whatever is written to it. Dropping those echoed lines afterwards
            // works only while an echoed line IS a line: a long command wraps
            // across several, and then the filter misses it and the fragments
            // land in the output. A `pm grant ...` line is long enough to do it.
            //
            // An earlier attempt ran `stty -echo` as a normal command and
            // desynchronised the session, because its own echo arrives under
            // the old setting while the reader is already expecting the new
            // one. Writing it raw and draining whatever comes back for a moment
            // sidesteps that entirely - nothing is being matched, so nothing
            // can fail to match.
            runCatching {
                out.write("stty -echo 2>/dev/null; PS1=''\n".toByteArray(Charsets.UTF_8))
                out.flush()
                val deadline = System.currentTimeMillis() + SETTLE_MS
                while (System.currentTimeMillis() < deadline) {
                    if (reader.ready()) reader.read() else Thread.sleep(20)
                }
            }
        }

        fun run(command: String): String {
            check(!closing) { "ADB shell is closing" }
            running = true
            try {
            val begin = "$marker-BEGIN"
            val end = "$marker-END"

            // ONE line, not three, and that is the whole trick.
            //
            // adbd gives back a PTY even for `exec:sh`, and a PTY echoes each
            // input LINE back immediately before the shell runs it. With the
            // markers on separate lines the echo interleaves with the output:
            //
            //   echo hello          <- echo of line 2
            //   hello               <- what line 2 printed
            //   echo <marker>-END   <- echo of line 3
            //
            // and no amount of line filtering is safe, because a long command
            // wraps across several lines and the fragments look like results.
            // `stty -echo` does not stick here either.
            //
            // Sent as a single line, ALL the echo happens before ANY output, so
            // discarding everything up to the first exact BEGIN removes it
            // whatever shape it arrived in. The echoed text always contains the
            // word "echo", so it can never be equal to the marker the shell
            // prints when it runs the line.
            //
            // Commands must therefore be single-line; use ';' rather than
            // newlines, which is what every caller here already does.
            val oneLine = command.replace('\n', ';')

            // The command goes in a SUBSHELL, and that is not decoration.
            //
            // Joining with semicolons alone produces `cmd & ; echo END` for any
            // command that ends in `&`, and `& ;` is a syntax error in sh. The
            // shell dies, the end marker never arrives, and the caller sees a
            // timeout rather than an error - which is exactly how the daemon
            // launch broke: it is the one command here that backgrounds itself.
            //
            // `( cmd & )` is valid, and so is `( cmd )`, so wrapping handles
            // both shapes without the caller having to think about it.
            out.write("echo $begin; ( $oneLine ) ; echo $end\n".toByteArray(Charsets.UTF_8))
            out.flush()

            while (true) {
                val line = reader.readLine() ?: throw java.io.IOException("shell stream closed")
                if (line.trimEnd() == begin) break
            }

            val sb = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: throw java.io.IOException("shell stream closed")
                if (line.trimEnd() == end) break
                sb.appendLine(line)
            }
            return sb.toString().trimEnd()
            } finally {
                running = false
                // Never close a BufferedReader from the watchdog while a
                // concurrent readLine holds its internal lock.
                if (closing) runCatching { reader.close() }
            }
        }

        fun close() {
            closing = true
            // Close transport first so a pending readLine can unblock.
            runCatching { if (!stream.isClosed) stream.close() }
            runCatching { out.close() }
            if (!running) runCatching { reader.close() }
        }

        private companion object {
            /** Long enough for the shell to apply stty and stop echoing. */
            const val SETTLE_MS = 400L
        }
    }

    /** Switch the already-authorized TLS adbd session to legacy TCP/5555.
     * adbd may close the TLS stream as soon as the command is accepted.
     * The caller must reconnect to localhost and reverify UID 2000. */
    suspend fun switchToTcpip(port: Int = LEGACY_TCP_PORT): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(port == LEGACY_TCP_PORT)
                val mgr = manager ?: error("ADB is not connected")
                check(mgr.isConnected) { "ADB is not connected" }
                val stream = mgr.openStream("tcpip:$port")
                delay(500L)
                runCatching { stream.close() }
                runCatching { shell?.close() }
                shell = null
                runCatching { mgr.disconnect() }
                lastKnownConnected = false
                Unit
            }
        }

    /** Ask adbd to leave TCP mode and return to USB, when connected. */
    suspend fun switchToUsb(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val mgr = manager ?: error("ADB is not connected")
            check(mgr.isConnected) { "ADB is not connected" }
            mgr.openStream("usb:").let { stream ->
                // Mirror tcpip: allow adbd to accept the mode change before
                // tearing down the stream. The previous transport may drop.
                delay(400L)
                runCatching { stream.close() }
            }
            runCatching { shell?.close() }
            shell = null
            runCatching { mgr.disconnect() }
            lastKnownConnected = false
            Unit
        }
    }

    /** Disconnect and retire the manager, including its disposable session key. */
    suspend fun disconnect() = close()

    /**
     * Release the transport and in-memory manager, but preserve our ADB identity.
     *
     * libadb AbsAdbConnectionManager.close() calls getPrivateKey().destroy()
     * and prints DestroyFailedException if the persistent key cannot be erased.
     * Use disconnect() for socket-only teardown on reconnect, Stop and Quit.
     */
    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { shell?.close() }
        shell = null
        val oldManager = manager
        manager = null
        runCatching { oldManager?.disconnect() }
        lastKnownConnected = false
        Unit
    }
}
