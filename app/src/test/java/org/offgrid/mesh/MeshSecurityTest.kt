package org.offgrid.mesh

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MeshSecurityTest {

    @Before
    fun setUp() {
        MeshSecurity.resetToDefault()
    }

    @Test
    fun testAesGcmEncryptDecryptRoundtrip() {
        val cleartext = "Coordinates: 17.3850° N, 78.4867° E | 4 survivors trapped"
        val ciphertext = MeshSecurity.encrypt(cleartext)

        assertTrue(ciphertext.startsWith("v1:"))
        assertNotEquals(cleartext, ciphertext)

        val decrypted = MeshSecurity.decrypt(ciphertext)
        assertEquals(cleartext, decrypted)
    }

    @Test
    fun testHmacSignDeterministic() {
        val payload = "packet-123|NODE-A|SOS|payload|1730000000|5||MESSAGE|"
        val sig1 = MeshSecurity.sign(payload)
        val sig2 = MeshSecurity.sign(payload)

        assertNotNull(sig1)
        assertEquals(sig1, sig2)
        assertEquals(64, sig1.length) // 256 bits = 64 hex characters
    }

    @Test
    fun testCustomGroupCodeIsolation() {
        val message = "Secret team transmission"

        MeshSecurity.setGroupCode("ZONE-ALPHA-SECURE")
        val encryptedWithAlpha = MeshSecurity.encrypt(message)
        val sigWithAlpha = MeshSecurity.sign(message)

        // Switch to different rescue group code
        MeshSecurity.setGroupCode("ZONE-BRAVO-DIFFERENT")

        // Decryption must fail or throw when using wrong key
        assertThrows(Exception::class.java) {
            MeshSecurity.decrypt(encryptedWithAlpha)
        }

        // Signatures must not match across groups
        val sigWithBravo = MeshSecurity.sign(message)
        assertNotEquals(sigWithAlpha, sigWithBravo)
    }

    @Test
    fun testTamperedCiphertextFailsDecryption() {
        val cleartext = "Critical supplies delivered"
        val ciphertext = MeshSecurity.encrypt(cleartext)

        // Modify ciphertext character
        val tampered = ciphertext.dropLast(2) + "=="

        assertThrows(Exception::class.java) {
            MeshSecurity.decrypt(tampered)
        }
    }
}
