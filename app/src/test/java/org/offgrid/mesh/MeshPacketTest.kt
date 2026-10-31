package org.offgrid.mesh

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class MeshPacketTest {

    @Before
    fun setUp() {
        MeshSecurity.resetToDefault()
    }

    @Test
    fun testPacketDefaultValues() {
        val packet = MeshPacket(sender = "DEVICE_A", priority = "SOS", body = "Medical assistance required")
        assertNotNull(UUID.fromString(packet.id))
        assertEquals("DEVICE_A", packet.sender)
        assertEquals("SOS", packet.priority)
        assertEquals("Medical assistance required", packet.body)
        assertEquals(5, packet.ttl)
        assertTrue(packet.hops.isEmpty())
        assertEquals(MeshPacket.TYPE_MESSAGE, packet.type)
        assertNull(packet.receiptFor)
        assertTrue(packet.createdAt > 0)
    }

    @Test
    fun testForwardThroughDecrementsTtlAndAppendsHop() {
        val initial = MeshPacket(sender = "ORIGIN", priority = "SOS", body = "Flood warning", ttl = 5)
        val hop1 = initial.forwardThrough("RELAY_1")
        assertEquals(4, hop1.ttl)
        assertEquals(listOf("RELAY_1"), hop1.hops)

        val hop2 = hop1.forwardThrough("RELAY_2")
        assertEquals(3, hop2.ttl)
        assertEquals(listOf("RELAY_1", "RELAY_2"), hop2.hops)
    }

    @Test
    fun testEncodeDecodeRoundtrip() {
        val original = MeshPacket(
            id = "test-uuid-1234",
            sender = "SENDER_NODE",
            priority = "GENERAL",
            body = "Bridge on North River is submerged",
            createdAt = 1730000000000L,
            ttl = 4,
            hops = listOf("RELAY_A", "RELAY_B")
        ).signed()

        val json = original.encode()
        // Wire body must be encrypted and not leak raw cleartext
        assertFalse(json.contains("Bridge on North River is submerged"))
        assertTrue(json.contains("v1:"))

        val decoded = MeshPacket.decode(json)
        assertEquals(original.id, decoded.id)
        assertEquals(original.sender, decoded.sender)
        assertEquals(original.priority, decoded.priority)
        assertEquals(original.body, decoded.body)
        assertEquals(original.createdAt, decoded.createdAt)
        assertEquals(original.ttl, decoded.ttl)
        assertEquals(original.hops, decoded.hops)
        assertEquals(original.auth, decoded.auth)
        assertTrue(decoded.isAuthentic())
    }

    @Test
    fun testAuthenticityAndTamperDetection() {
        val authentic = MeshPacket(sender = "NODE_1", priority = "SOS", body = "Need rescue").signed()
        assertTrue(authentic.isAuthentic())

        // Tampering with body must invalidate signature
        val tamperedBody = authentic.copy(body = "False alarm")
        assertFalse(tamperedBody.isAuthentic())

        // Tampering with priority must invalidate signature
        val tamperedPriority = authentic.copy(priority = "GENERAL")
        assertFalse(tamperedPriority.isAuthentic())

        // Tampering with sender must invalidate signature
        val tamperedSender = authentic.copy(sender = "IMPOSTOR")
        assertFalse(tamperedSender.isAuthentic())
    }

    @Test
    fun testReceiptPacketEncoding() {
        val targetMessageId = "orig-msg-999"
        val receipt = MeshPacket(
            sender = "RESPONDER_BASE",
            priority = "RECEIPT",
            body = "",
            ttl = 3,
            type = MeshPacket.TYPE_RECEIPT,
            receiptFor = targetMessageId
        ).signed()

        val json = receipt.encode()
        val decoded = MeshPacket.decode(json)

        assertEquals(MeshPacket.TYPE_RECEIPT, decoded.type)
        assertEquals(targetMessageId, decoded.receiptFor)
        assertTrue(decoded.isAuthentic())
    }
}
