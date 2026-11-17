package dev.n2k02.mcplogger.adb

import android.content.Context
import dev.n2k02.mcplogger.core.MonitorState
import dev.n2k02.mcplogger.shizuku.ShizukuPhase
import dev.n2k02.mcplogger.shizuku.ShizukuSession
import dev.n2k02.mcplogger.shizuku.ShizukuState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Shared status and lifecycle for one Shizuku Binder session, never a TCP ADB client. */
object AdbCoordinator {
    val verified = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindingLock = Any()
    private var observedCore: ShizukuSession? = null
    private var observer: Job? = null
    private var serviceOwner = false

    private fun core(context: Context): ShizukuSession {
        val session = ShizukuHostAdapter.core(context.applicationContext)
        synchronized(bindingLock) {
            if (observedCore !== session) {
                observer?.cancel()
                observedCore = session
                publish(session.state.value)
                observer = scope.launch { session.state.collect { publish(it) } }
            }
        }
        return session
    }

    private fun publish(state: ShizukuState) {
        verified.value = state.connected
        if (state.phase == ShizukuPhase.STOPPED) {
            synchronized(bindingLock) { serviceOwner = false }
        }
        MonitorState.adb.value = if (state.connected) "UID 2000 verified · Shizuku"
            else state.message
    }

    /** Monitoring may own the Shizuku session without the Activity remaining visible. */
    @Synchronized fun discover(context: Context) {
        if (AdbRecoverySettings.manuallyQuit(context)) return
        val session = core(context)
        if (!serviceOwner) {
            serviceOwner = true
            session.acquireService()
        }
    }

    @Synchronized fun stopDiscovery() {
        if (serviceOwner) {
            observedCore?.releaseService()
            serviceOwner = false
        }
    }

    suspend fun connect(context: Context, userInitiated: Boolean = false): Result<Unit> {
        val app = context.applicationContext
        if (AdbRecoverySettings.manuallyQuit(app) && !userInitiated)
            return Result.failure(IllegalStateException("Stopped by Quit"))
        return try {
            if (userInitiated) AdbRecoverySettings.setManuallyQuit(app, false)
            val session = core(app)
            check(session.retry(auto = !userInitiated) && session.state.value.connected) {
                session.state.value.message
            }
            publish(session.state.value)
            if (userInitiated) discover(app)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            verified.value = false
            MonitorState.error("Shizuku access: " + (failure.message ?: "unavailable"))
            Result.failure(failure)
        }
    }

    fun autoStart(context: Context) = AdbRecoverySettings.startOnBoot(context)

    suspend fun quit() {
        stopDiscovery()
        ShizukuHostAdapter.shutdown()
        synchronized(bindingLock) {
            observer?.cancel()
            observer = null
            observedCore = null
        }
        scope.coroutineContext.cancelChildren()
        verified.value = false
        MonitorState.adb.value = "Stopped"
    }
}
