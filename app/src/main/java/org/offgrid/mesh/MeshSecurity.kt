package org.offgrid.mesh

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Shared rescue-group secret. Participants must use the same code to read or authenticate packets. */
object MeshSecurity {
    private const val PREFS = "offgrid_security"
    private const val KEY = "group_code"
    private const val DEFAULT_CODE = "OFFGRID-LOCAL-MESH-V1"
    @Volatile private var groupCode = DEFAULT_CODE

    fun load(context: Context) { groupCode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT_CODE) ?: DEFAULT_CODE }
    
    fun setGroupCode(context: Context, value: String) {
        setGroupCode(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, groupCode).apply()
    }

    fun setGroupCode(value: String) {
        groupCode = value.trim().ifBlank { DEFAULT_CODE }
    }

    fun getGroupCode(): String = groupCode

    fun resetToDefault() {
        groupCode = DEFAULT_CODE
    }

    fun hasCustomCode() = groupCode != DEFAULT_CODE

    fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(groupCode.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun encrypt(cleartext: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return "v1:" + Base64.getEncoder().encodeToString(iv + cipher.doFinal(cleartext.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(value: String): String {
        if (!value.startsWith("v1:")) return value
        val bytes = Base64.getDecoder().decode(value.removePrefix("v1:"))
        require(bytes.size > 12) { "Invalid encrypted packet" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private fun key() = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(groupCode.toByteArray(Charsets.UTF_8)).copyOf(16), "AES")
}
