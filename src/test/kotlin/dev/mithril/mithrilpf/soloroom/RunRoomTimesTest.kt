package dev.mithril.mithrilpf.soloroom

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import kotlin.test.*

class RunRoomTimesTest {
    private val times = RunRoomTimes()

    private fun map(vararg rooms: List<Int>) =
        JsonObject().apply {
            add(
                "rooms",
                JsonArray().apply {
                    rooms.forEach { tiles ->
                        add(
                            JsonObject().apply {
                                add("tiles", JsonArray().apply { tiles.forEach(::add) })
                            }
                        )
                    }
                },
            )
        }

    @Test
    fun `repeat visits preserve both clocks without per segment rounding loss`() {
        val data = map(listOf(0), listOf(1))
        times.begin(TimerStamp(100, 1_000_000_000), -185.0, -185.0)
        times.observe(TimerStamp(102, 1_001_600_001), -153.0, -185.0)
        times.observe(TimerStamp(103, 1_003_200_002), -185.0, -185.0)
        times.observe(TimerStamp(104, 1_004_800_003), -185.0, -185.0)
        val stats = assertNotNull(times.attach(data, TimerStamp(106, 1_005_000_000)))
        val rooms = data["rooms"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf(3L, 2L), rooms.map { it["elapsed_ms"].asLong })
        assertEquals(listOf(5L, 1L), rooms.map { it["ticks"].asLong })
        assertEquals(5, stats["elapsed_ms"].asInt)
        assertEquals(6, stats["ticks"].asInt)
        assertEquals(0, stats["transit_ms"].asInt)
    }

    @Test
    fun `internal joins belong to merged rooms while doorways and missing tiles are transit`() {
        val data = map(listOf(0, 1, 6, 7), listOf(2))
        times.begin(TimerStamp(0, 0), -169.0, -185.0)
        times.observe(TimerStamp(10, 1_000_000_000), -185.0, -169.0)
        times.observe(TimerStamp(20, 2_000_000_000), -169.0, -169.0)
        times.observe(TimerStamp(30, 3_000_000_000), -137.0, -185.0)
        times.observe(TimerStamp(40, 4_000_000_000), -25.0, -25.0)
        times.observe(TimerStamp(50, 5_000_000_000), 0.0, 0.0)
        val stats = assertNotNull(times.attach(data, TimerStamp(60, 6_000_000_000)))
        assertEquals(3000, data["rooms"].asJsonArray[0].asJsonObject["elapsed_ms"].asInt)
        assertEquals(0, data["rooms"].asJsonArray[1].asJsonObject["ticks"].asInt)
        assertEquals(3000, stats["transit_ms"].asInt)
        assertEquals(30, stats["transit_ticks"].asInt)
        // The same junction is not part of an L-shaped room missing its fourth tile.
        val partial = map(listOf(0, 1, 6), listOf(2))
        val partialStats = assertNotNull(times.attach(partial, TimerStamp(60, 6_000_000_000)))
        assertEquals(4000, partialStats["transit_ms"].asInt)
    }

    @Test
    fun `full run accounting includes boundaries and resets between runs`() {
        val data = map(listOf(0), listOf(35))
        assertNull(times.attach(data, TimerStamp(0, 0)))
        times.observe(TimerStamp(0, 0), -185.0, -185.0)
        times.begin(TimerStamp(0, 0), -200.0, -200.0)
        times.observe(TimerStamp(20, 1_000_000_000), -9.01, -9.01)
        times.observe(TimerStamp(40, 2_000_000_000), -9.0, -9.0)
        times.observe(TimerStamp(40, 3_000_000_000), Double.NaN, 0.0)
        times.observe(TimerStamp(60, 4_000_000_000), -185.0, Double.POSITIVE_INFINITY)
        times.observe(TimerStamp(80, 5_000_000_000), -200.01, -185.0)
        val stats = assertNotNull(times.attach(data, TimerStamp(100, 6_000_000_000)))
        assertEquals(4000, stats["transit_ms"].asInt)
        assertEquals(60, stats["transit_ticks"].asInt)
        for ((field, transit) in listOf("elapsed_ms" to "transit_ms", "ticks" to "transit_ticks")) {
            assertEquals(
                stats[field].asLong,
                data["rooms"].asJsonArray.sumOf { it.asJsonObject[field].asLong } +
                    stats[transit].asLong,
            )
        }
        times.begin(TimerStamp(200, 10_000_000_000), -25.0, -25.0)
        val fresh = assertNotNull(times.attach(data, TimerStamp(220, 11_000_000_000)))
        assertEquals(0, data["rooms"].asJsonArray[0].asJsonObject["ticks"].asInt)
        assertEquals(20, data["rooms"].asJsonArray[1].asJsonObject["ticks"].asInt)
        assertEquals(0, fresh["transit_ticks"].asInt)
    }
}
