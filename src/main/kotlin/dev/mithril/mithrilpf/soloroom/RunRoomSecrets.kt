package dev.mithril.mithrilpf.soloroom

import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

internal data class RoomSecretEvent(val ms: Int, val tile: Int, val found: Int, val total: Int)

/** Sparse counter observations, bounded to 100 increases for each of 36 tiles. */
internal class RunRoomSecrets {
    private val highest = IntArray(36)
    private val events = mutableListOf<RoomSecretEvent>()

    fun observe(ms: Long, tile: Int, found: Int, total: Int) {
        if (tile !in 0..35 || ms !in 0..7_200_000 || total !in 0..100 || found !in 1..total) return
        if (found <= highest[tile] || ms < (events.lastOrNull()?.ms ?: 0)) return
        highest[tile] = found
        events.add(RoomSecretEvent(ms.toInt(), tile, found, total))
    }

    fun freeze(map: JsonObject, duration: Long): List<RoomSecretEvent> {
        val owners = arrayOfNulls<JsonObject>(36)
        map.getAsJsonArray("rooms").forEach { entry ->
            val room = entry.asJsonObject
            room.getAsJsonArray("tiles").forEach { owners[it.asInt] = room }
        }
        val counts = IntArray(36)
        return events.mapNotNull { event ->
            val room = owners[event.tile] ?: return@mapNotNull null
            val tile = room.getAsJsonArray("tiles")[0].asInt
            val total = room["secrets_total"].takeUnless { it.isJsonNull }?.asInt
            val found = room["secrets_found"].takeUnless { it.isJsonNull }?.asInt
            if (
                event.ms > duration ||
                    (total != null && event.total != total) ||
                    (found != null && event.found > found) ||
                    event.found <= counts[tile]
            )
                return@mapNotNull null
            counts[tile] = event.found
            event.copy(tile = tile)
        }
    }
}

/** Called only by the sync worker, alongside position-sample encoding. */
internal fun encodeRoomSecrets(events: List<RoomSecretEvent>): String {
    val bytes = ByteBuffer.allocate(events.size * 6).order(ByteOrder.LITTLE_ENDIAN)
    events.forEach { bytes.putInt(it.ms).put(it.tile.toByte()).put(it.found.toByte()) }
    return Base64.getEncoder().encodeToString(bytes.array())
}
