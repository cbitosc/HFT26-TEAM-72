package org.offgrid.mesh

import org.junit.Assert.*
import org.junit.Test

class MeshCompressionTest {

    @Test
    fun testSmallPayloadNotCompressed() {
        val shortMessage = "Hello node"
        val compressed = MeshCompression.compress(shortMessage)
        // Small messages should not be compressed because GZIP headers add overhead
        assertEquals(shortMessage, compressed)
        assertFalse(MeshCompression.isCompressed(compressed))
    }

    @Test
    fun testLargeRepetitivePayloadCompressed() {
        val repetitive = "EMERGENCY ALERT: SEVERE FLASH FLOOD AT SECTOR 4! ".repeat(15)
        val compressed = MeshCompression.compress(repetitive)

        assertTrue(MeshCompression.isCompressed(compressed))
        assertTrue(compressed.startsWith(MeshCompression.PREFIX))
        assertTrue(compressed.length < repetitive.length)

        val savings = MeshCompression.savingsPercentage(repetitive, compressed)
        assertTrue(savings > 30.0)
    }

    @Test
    fun testDecompressRestoresOriginalString() {
        val original = "Medical triage established at coordinate 17.4125,78.3982. Requesting insulin and sterile bandages. ".repeat(8)
        val compressed = MeshCompression.compress(original)
        val decompressed = MeshCompression.decompress(compressed)

        assertEquals(original, decompressed)
    }

    @Test
    fun testDecompressUncompressedPayload() {
        val raw = "Plain uncompressed status update"
        val result = MeshCompression.decompress(raw)
        // Should return original payload intact for backward compatibility
        assertEquals(raw, result)
    }

    @Test
    fun testEdgeCases() {
        assertEquals("", MeshCompression.compress(""))
        assertEquals("", MeshCompression.decompress(""))
        assertEquals(0.0, MeshCompression.savingsPercentage("", ""), 0.001)
    }
}
