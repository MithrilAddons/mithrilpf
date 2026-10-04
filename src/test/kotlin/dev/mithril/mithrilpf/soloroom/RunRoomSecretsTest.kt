package dev.mithril.mithrilpf.soloroom

import com.google.gson.JsonParser
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import java.util.Base64
import kotlin.test.*

class RunRoomSecretsTest {
    private fun stamp(ms: Long) = TimerStamp(ms / 50, ms * 1_000_000)

    private fun map() =
        JsonParser.parseString(javaClass.getResource("/contracts/run-map-v2.json")!!.readText())
            .asJsonObject

    @Test
    fun `merged tiles revisits and duplicate counters produce one room timeline`() {
        val counters = RunRoomSecrets()
        counters.observe(200, 0, 1, 5)
        counters.observe(300, 0, 1, 5)
        counters.observe(400, 1, 1, 5)
        counters.observe(600, 1, 2, 5)
        counters.observe(1000, 0, 3, 5)
        counters.observe(15001, 0, 4, 5)
        val result = counters.freeze(map(), 15000)
        assertEquals(listOf(200, 600, 1000), result.map { it.ms })
        assertEquals(listOf(0, 0, 0), result.map { it.tile })
        val fixture =
            JsonParser.parseString(
                    javaClass.getResource("/contracts/run-replay-room-secrets-v1.json")!!.readText()
                )
                .asJsonObject
        assertEquals(fixture["room_secrets"].asString, encodeRoomSecrets(result))
    }

    @Test
    fun `invalid counters absent rooms and observations beyond final counts are omitted`() {
        val counters = RunRoomSecrets()
        counters.observe(-1, 0, 1, 5)
        counters.observe(1, -1, 1, 5)
        counters.observe(1, 36, 1, 5)
        counters.observe(1, 0, 0, 5)
        counters.observe(1, 0, 6, 5)
        counters.observe(1, 0, 1, 101)
        counters.observe(7_200_001, 0, 1, 5)
        counters.observe(200, 0, 1, 5)
        counters.observe(199, 1, 2, 5)
        counters.observe(300, 0, 2, 6)
        counters.observe(400, 35, 1, 5)
        counters.observe(500, 7, 1, 5)
        counters.observe(600, 0, 4, 5)
        assertEquals(listOf(RoomSecretEvent(200, 0, 1, 5)), counters.freeze(map(), 15000))
    }

    @Test
    fun `room counters stop with replay and reset for a new run`() {
        val replay = RunReplay()
        replay.observeRoomSecrets(stamp(0), 0, 1, 5)
        replay.begin(stamp(100), -185.0, -185.0, 0f)
        replay.observeRoomSecrets(stamp(300), 1, 1, 5)
        replay.observe(stamp(500), -185.0, -185.0, 0f, 1, finish = true)
        val frozen = assertNotNull(replay.freeze(map())).encode()
        assertEquals(
            encodeRoomSecrets(listOf(RoomSecretEvent(200, 0, 1, 5))),
            frozen["room_secrets"].asString,
        )
        replay.observeRoomSecrets(stamp(700), 0, 2, 5)
        replay.begin(stamp(1000), -185.0, -185.0, 0f)
        replay.observe(stamp(1200), -185.0, -185.0, 0f, 0, finish = true)
        assertEquals("", assertNotNull(replay.freeze(map())).encode()["room_secrets"].asString)
        assertEquals(6, Base64.getDecoder().decode(frozen["room_secrets"].asString).size)
    }

    @Test
    fun `counter storage remains bounded across repeated observations`() {
        val counters = RunRoomSecrets()
        val map = map()
        val room = map.getAsJsonArray("rooms")[0].asJsonObject
        room.addProperty("secrets_found", 100)
        room.addProperty("secrets_total", 100)
        repeat(3) {
            for (found in 1..100) counters.observe(found.toLong(), 0, found, 100)
        }
        assertEquals(
            100 * 6,
            Base64.getDecoder().decode(encodeRoomSecrets(counters.freeze(map, 100))).size,
        )
        room.addProperty("secrets_found", null as Number?)
        room.addProperty("secrets_total", null as Number?)
        assertEquals(100, counters.freeze(map, 100).size)
    }
}
