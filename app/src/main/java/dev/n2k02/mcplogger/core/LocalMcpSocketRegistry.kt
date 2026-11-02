package dev.n2k02.mcplogger.core

import java.net.Socket

/** Accepted TCP sockets are owned by one listener generation. */
internal class LocalMcpSocketRegistry(private val maxClients: Int = 32) {
    init { require(maxClients > 0) }
    private val sockets = mutableSetOf<Socket>()
    private var generation = 0L
    private var accepting = false

    @Synchronized fun begin(): Long {
        check(!accepting)
        accepting = true
        return ++generation
    }

    @Synchronized fun isCurrent(id: Long) = accepting && id == generation

    fun register(id: Long, client: Socket): Boolean {
        val accepted = synchronized(this) {
            if (!accepting || generation != id || sockets.size >= maxClients) false
            else sockets.add(client)
        }
        if (!accepted) runCatching { client.close() }
        return accepted
    }

    @Synchronized fun release(client: Socket) { sockets.remove(client) }

    fun stop() {
        val live = synchronized(this) {
            accepting = false
            generation++
            sockets.toList().also { sockets.clear() }
        }
        live.forEach { runCatching { it.close() } }
    }
}
