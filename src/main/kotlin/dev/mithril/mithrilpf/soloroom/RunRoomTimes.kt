package dev.mithril.mithrilpf.soloroom

import com.google.gson.JsonObject
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import kotlin.math.floor

/** Fixed-size position buckets; room ownership is resolved from the final map, not room names. */
class RunRoomTimes {
    private val millis = LongArray(122)
    private val ticks = LongArray(122)
    private var start: TimerStamp? = null
    private var elapsedMillis = 0L
    private var elapsedTicks = 0L
    private var position = 121

    fun begin(now: TimerStamp, x: Double, z: Double) {
        start = now
        millis.fill(0)
        ticks.fill(0)
        elapsedMillis = 0
        elapsedTicks = 0
        position = position(x, z)
    }

    fun observe(now: TimerStamp, x: Double, z: Double) {
        advance(now)
        position = position(x, z)
    }

    private fun advance(now: TimerStamp) {
        val elapsed = now - (start ?: return)
        // Difference of run-relative milliseconds avoids losing fractions at every room change.
        millis[position] += elapsed.realMillis - elapsedMillis
        ticks[position] += elapsed.ticks - elapsedTicks
        elapsedMillis = elapsed.realMillis
        elapsedTicks = elapsed.ticks
    }

    fun attach(map: JsonObject, now: TimerStamp): JsonObject? {
        if (start == null) return null
        advance(now)
        val rooms = map["rooms"].asJsonArray.map { it.asJsonObject }
        val owners =
            rooms
                .flatMapIndexed { index, room ->
                    room["tiles"].asJsonArray.map { it.asInt to index }
                }
                .toMap()
        val roomMillis = LongArray(rooms.size)
        val roomTicks = LongArray(rooms.size)
        for (cell in 0..120) {
            val adjacent = tiles(cell)
            val owner = owners[adjacent.first()] ?: continue
            // Internal joins of a multi-tile room belong to it; doors between rooms are transit.
            if (adjacent.any { owners[it] != owner }) continue
            roomMillis[owner] += millis[cell]
            roomTicks[owner] += ticks[cell]
        }
        rooms.forEachIndexed { index, room ->
            room.addProperty("elapsed_ms", roomMillis[index])
            room.addProperty("ticks", roomTicks[index])
        }
        return JsonObject().apply {
            addProperty("elapsed_ms", elapsedMillis)
            addProperty("ticks", elapsedTicks)
            addProperty("transit_ms", elapsedMillis - roomMillis.sum())
            addProperty("transit_ticks", elapsedTicks - roomTicks.sum())
        }
    }

    private fun position(x: Double, z: Double): Int {
        fun axis(value: Double): Int? {
            if (!value.isFinite()) return null
            val block = floor(value + 200)
            if (block !in 0.0..190.0) return null
            val coordinate = block.toInt()
            return coordinate / 32 * 2 + if (coordinate % 32 == 31) 1 else 0
        }
        val column = axis(x) ?: return 121
        val row = axis(z) ?: return 121
        return row * 11 + column
    }

    private fun tiles(cell: Int): List<Int> {
        val x = cell % 11
        val z = cell / 11
        val columns = if (x % 2 == 0) listOf(x / 2) else listOf(x / 2, x / 2 + 1)
        val rows = if (z % 2 == 0) listOf(z / 2) else listOf(z / 2, z / 2 + 1)
        return rows.flatMap { row -> columns.map { row * 6 + it } }
    }
}
