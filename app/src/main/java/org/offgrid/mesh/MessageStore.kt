package org.offgrid.mesh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable local outbox. It survives a process restart and is replayed when a peer reconnects. */
class MessageStore(context: Context) {
    data class Entry(val packet: MeshPacket, val state: String, val updatedAt: Long = System.currentTimeMillis())

    private val prefs = context.getSharedPreferences("offgrid_outbox", Context.MODE_PRIVATE)
    @Synchronized fun pending(): List<Entry> = read().filter { it.state == PENDING || it.state == SENT_TO_PEER }
        .sortedWith(compareBy<Entry> { priorityRank(it.packet.priority) }.thenBy { it.packet.createdAt })

    @Synchronized fun save(packet: MeshPacket, state: String) {
        val entries = read().filterNot { it.packet.id == packet.id }.toMutableList()
        entries += Entry(packet, state)
        write(entries)
    }

    @Synchronized fun update(id: String, state: String) {
        write(read().map {
            if (it.packet.id != id || (it.state == ACKNOWLEDGED && state != ACKNOWLEDGED)) it
            else it.copy(state = state, updatedAt = System.currentTimeMillis())
        })
    }

    private fun read(): List<Entry> = try {
        val array = JSONArray(prefs.getString("entries", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Entry(MeshPacket.decode(item.getString("packet")), item.getString("state"), item.optLong("updatedAt"))
        }
    } catch (_: Exception) { emptyList() }

    private fun write(entries: List<Entry>) {
        // Retain the latest 200 entries so prolonged deployments cannot grow storage without bound.
        val array = JSONArray()
        entries.takeLast(200).forEach { entry ->
            array.put(JSONObject().put("packet", entry.packet.encode()).put("state", entry.state).put("updatedAt", entry.updatedAt))
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    companion object {
        const val PENDING = "PENDING — waiting for a peer"
        const val SENT_TO_PEER = "SENT — handed to a nearby peer"
        const val RELAYED = "RELAYED — another device confirmed receipt"
        const val ACKNOWLEDGED = "ACKNOWLEDGED — responder confirmed receipt"
        private fun priorityRank(priority: String) = when (priority) { "SOS" -> 0; "MEDICAL" -> 1; else -> 2 }
    }
}
