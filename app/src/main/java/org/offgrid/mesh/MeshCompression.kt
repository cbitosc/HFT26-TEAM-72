package org.offgrid.mesh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Lightweight radio payload compression engine for constrained BLE and RFCOMM transports.
 * Automatically compresses text payloads exceeding size thresholds to reduce packet fragmentation
 * across lossy peer-to-peer radio channels.
 *
 * Employs a 'z1:' prefix and maintains 100% backward compatibility with uncompressed text.
 */
object MeshCompression {
    const val PREFIX = "z1:"
    private const val MIN_COMPRESS_BYTES = 80

    /**
     * Compresses the cleartext payload if its UTF-8 size exceeds the threshold
     * and compression yields a smaller wire representation.
     */
    fun compress(cleartext: String): String {
        val inputBytes = cleartext.toByteArray(Charsets.UTF_8)
        if (inputBytes.size < MIN_COMPRESS_BYTES) return cleartext

        return try {
            val byteStream = ByteArrayOutputStream()
            GZIPOutputStream(byteStream).use { gzip ->
                gzip.write(inputBytes)
            }
            val compressedBytes = byteStream.toByteArray()
            val encoded = PREFIX + Base64.getEncoder().encodeToString(compressedBytes)

            // Only return compressed if it actually saved space over raw text
            if (encoded.length < cleartext.length) encoded else cleartext
        } catch (_: Exception) {
            cleartext
        }
    }

    /**
     * Decompresses the payload if it begins with the 'z1:' compression header.
     * Otherwise returns the original text untouched.
     */
    fun decompress(payload: String): String {
        if (!payload.startsWith(PREFIX)) return payload

        return try {
            val base64Data = payload.removePrefix(PREFIX)
            val compressedBytes = Base64.getDecoder().decode(base64Data)
            val byteStream = ByteArrayInputStream(compressedBytes)
            GZIPInputStream(byteStream).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
            }
        } catch (_: Exception) {
            payload
        }
    }

    /**
     * Returns true if the payload has been compressed with the 'z1:' header.
     */
    fun isCompressed(payload: String): Boolean = payload.startsWith(PREFIX)

    /**
     * Calculates the compression ratio savings percentage (0.0% to 100.0%).
     */
    fun savingsPercentage(original: String, compressed: String): Double {
        if (original.isEmpty()) return 0.0
        val saved = original.length - compressed.length
        if (saved <= 0) return 0.0
        return (saved.toDouble() / original.length.toDouble()) * 100.0
    }
}
