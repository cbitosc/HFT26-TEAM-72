package org.offgrid.mesh

import org.json.JSONObject
import java.util.UUID

/** Wire format shared by Bluetooth RFCOMM and Wi-Fi Direct TCP transports. */
data class MeshPacket(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val priority: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val ttl: Int = 5,
    val hops: List<String> = emptyList(),
    val type: String = TYPE_MESSAGE,
    val receiptFor: String? = null,
    val auth: String = ""
) {
    fun forwardThrough(deviceId: String) = copy(ttl = ttl - 1, hops = hops + deviceId)

    private fun signingPayload() = listOf(id, sender, priority, body, createdAt, ttl, hops.joinToString(","), type, receiptFor.orEmpty()).joinToString("|")
    fun signed() = copy(auth = MeshSecurity.sign(signingPayload()))
    fun isAuthentic() = auth.isNotBlank() && constantTimeEquals(auth, MeshSecurity.sign(signingPayload()))

    fun encode(): String = JSONObject().apply {
        put("id", id); put("sender", sender); put("priority", priority)
        put("body", MeshSecurity.encrypt(MeshCompression.compress(body)))
        put("createdAt", createdAt); put("ttl", ttl); put("hops", hops.joinToString(","))
        put("type", type); put("receiptFor", receiptFor); put("auth", auth)
    }.toString()

    companion object {
        fun decode(json: String): MeshPacket {
            val o = JSONObject(json)
            val decryptedBody = MeshSecurity.decrypt(o.getString("body"))
            val decompressedBody = MeshCompression.decompress(decryptedBody)
            return MeshPacket(o.getString("id"), o.getString("sender"), o.getString("priority"),
                decompressedBody, o.getLong("createdAt"), o.getInt("ttl"),
                o.optString("hops").split(',').filter { it.isNotBlank() },
                o.optString("type", TYPE_MESSAGE), o.optString("receiptFor").ifBlank { null }, o.optString("auth"))
        }

        const val TYPE_MESSAGE = "MESSAGE"
        const val TYPE_RECEIPT = "RECEIPT"
        const val TYPE_RESPONDER_ACK = "RESPONDER_ACK"
        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
