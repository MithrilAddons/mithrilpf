package dev.mithril.mithrilpf.soloroom

import kotlin.test.*

class RunMapCaptureTest {
    private val capture = RunMapCapture()
    private val map = SoloRoomMap(5, 5, 16)
    private val colors = ByteArray(16384)
    private val definitions = MutableList<SoloRoomDefinition?>(36) { null }

    private fun pixel(x: Int, z: Int, color: Int) {
        colors[z * 128 + x] = color.toByte()
    }

    private fun room(tile: Int, center: Int = 34, type: Int = 63, total: Int = 5) {
        val x = 5 + tile % 6 * 20
        val z = 5 + tile / 6 * 20
        pixel(x, z, type)
        pixel(x + 8, z + 8, center)
        definitions[tile] = SoloRoomDefinition("Synthetic room", "NORMAL", emptyList(), total)
    }

    private fun snapshot() = assertNotNull(capture.snapshot(map, colors, definitions))

    private fun first() = snapshot()["rooms"].asJsonArray[0].asJsonObject

    @Test
    fun `unvisited rooms start at zero and observations persist across room changes`() {
        room(0)
        room(1)
        assertEquals(0, first()["secrets_found"].asInt)
        capture.visit(0)
        capture.observeSecrets(0, "100/100 Health   3/5 Secrets   100/100 Mana")
        capture.visit(1)
        capture.observeSecrets(1, "1/5 Secrets")
        val rooms = snapshot()["rooms"].asJsonArray
        assertEquals(3, rooms[0].asJsonObject["secrets_found"].asInt)
        assertEquals(1, rooms[1].asJsonObject["secrets_found"].asInt)
        val frozen = snapshot().toString()
        capture.observeSecrets(0, "4/5 Secrets")
        assertTrue(frozen.contains("\"secrets_found\":3"))
        assertEquals(4, first()["secrets_found"].asInt)
    }

    @Test
    fun `completed markers confirm total but missing visited observations are not fabricated`() {
        room(0)
        capture.visit(0)
        for (text in listOf("health 2/5", "6/5 Secrets", "1000/1000 Secrets", "2/6 Secrets")) {
            capture.observeSecrets(0, text)
        }
        assertTrue(first()["secrets_found"].isJsonNull)
        room(0, center = 30)
        assertEquals(5, first()["secrets_found"].asInt)
        assertEquals("COMPLETE", first()["state"].asString)
        room(0, total = 0)
        assertEquals(0, first()["secrets_found"].asInt)
    }

    @Test
    fun `connected segments form one room with shared counters and doors join separate rooms`() {
        room(0)
        room(1)
        room(7)
        room(2)
        pixel(23, 13, 63)
        pixel(23, 9, 63)
        pixel(33, 23, 63)
        pixel(29, 23, 63)
        pixel(43, 13, 119)
        capture.visit(1)
        capture.observeSecrets(1, "4/5 Secrets")
        val data = snapshot()
        assertEquals(2, data["rooms"].asJsonArray.size())
        val merged = data["rooms"].asJsonArray[0].asJsonObject
        assertEquals(listOf(0, 1, 7), merged["tiles"].asJsonArray.map { it.asInt })
        assertEquals(4, merged["secrets_found"].asInt)
        val door = data["doors"].asJsonArray.single().asJsonObject
        assertEquals(1, door["a"].asInt)
        assertEquals(2, door["b"].asInt)
        assertEquals("WITHER", door["type"].asString)
    }

    @Test
    fun `map types and states survive unknown room definitions`() {
        for ((type, color) in
            listOf(
                "BLOOD" to 18,
                "FAIRY" to 82,
                "RARE" to 34,
                "CHAMPION" to 74,
                "PUZZLE" to 66,
                "TRAP" to 62,
                "NORMAL" to 63,
                "ENTRANCE" to 30,
                "UNKNOWN" to 1,
            )) {
            room(0, center = 18, type = color)
            definitions[0] = null
            assertEquals(type, first()["type"].asString)
            assertEquals(
                if (type == "PUZZLE") "FAILED" else "DISCOVERED",
                first()["state"].asString,
            )
        }
        for ((color, state) in
            listOf(85 to "UNOPENED", 119 to "UNOPENED", 63 to "DISCOVERED", 0 to "UNKNOWN")) {
            room(0, center = color)
            assertEquals(state, first()["state"].asString)
        }
        assertEquals(listOf(6, 1), RunMapCapture.neighbors(0))
        assertEquals(listOf(29, 34), RunMapCapture.neighbors(35))
        assertFalse(6 in RunMapCapture.neighbors(5))
    }

    @Test
    fun `invalid capture inputs do not create a map`() {
        assertNull(capture.snapshot(map, colors, definitions))
        assertNull(capture.snapshot(null, colors, definitions))
        assertNull(capture.snapshot(map, ByteArray(8), definitions))
        assertNull(capture.snapshot(map, colors, emptyList()))
        capture.observeSecrets(null, "1/5 Secrets")
        capture.observeSecrets(36, "1/5 Secrets")
        for (tile in 0..4) room(tile)
        for (edge in 0..3) {
            pixel(23 + edge * 20, 13, 63)
            pixel(23 + edge * 20, 9, 63)
        }
        assertNull(capture.snapshot(map, colors, definitions))
    }
}
