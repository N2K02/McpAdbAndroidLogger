package dev.n2k02.mcplogger.core

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class LocalMcpSocketRegistryTest {
    private fun pair(): Pair<Socket, Socket> =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val peer = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
            peer to listener.accept()
        }

    @Test fun stopClosesAllAcceptedClientsAndIsIdempotent() {
        val registry = LocalMcpSocketRegistry()
        val generation = registry.begin()
        val (peer1, client1) = pair()
        val (peer2, client2) = pair()
        try {
            assertTrue(registry.register(generation, client1))
            assertTrue(registry.register(generation, client2))
            registry.stop()
            assertTrue(client1.isClosed)
            assertTrue(client2.isClosed)
            assertFalse(registry.isCurrent(generation))
            registry.stop()
        } finally {
            peer1.close(); peer2.close(); client1.close(); client2.close()
        }
    }

    @Test fun stopRestartNeverLetsOldListenerOwnNewClients() {
        val registry = LocalMcpSocketRegistry()
        val old = registry.begin()
        registry.stop()
        val next = registry.begin()
        val (peer, client) = pair()
        try {
            assertFalse(registry.isCurrent(old))
            assertTrue(registry.isCurrent(next))
            assertFalse(registry.register(old, client))
            assertTrue(client.isClosed)
        } finally {
            peer.close(); client.close(); registry.stop()
        }
    }

    @Test fun boundedAdmissionsRejectExcessConnectionsAndRecoverCapacity() {
        val registry = LocalMcpSocketRegistry(maxClients = 1)
        val generation = registry.begin()
        val (peer1, client1) = pair()
        val (peer2, client2) = pair()
        val (peer3, client3) = pair()
        try {
            assertTrue(registry.register(generation, client1))
            assertFalse(registry.register(generation, client2))
            assertTrue(client2.isClosed)
            client1.close()
            registry.release(client1)
            assertTrue(registry.register(generation, client3))
            registry.stop()
            assertTrue(client3.isClosed)
        } finally {
            peer1.close(); peer2.close(); peer3.close()
            client1.close(); client2.close(); client3.close()
        }
    }

    @Test fun releasedConnectionDoesNotGetReclaimedByStop() {
        val registry = LocalMcpSocketRegistry()
        val generation = registry.begin()
        val (peer, client) = pair()
        try {
            assertTrue(registry.register(generation, client))
            registry.release(client)
            registry.stop()
            assertFalse(client.isClosed)
        } finally {
            peer.close(); client.close()
        }
    }
}
