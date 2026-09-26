package dev.mithril.mithrilpf.soloroom

import kotlin.test.*

class SoloRoomMapTest {
    private fun map(size: Int, x: Int = 5, z: Int = 5) =
        ByteArray(16384).apply {
            for (i in 0 until size) this[z * 128 + x + i] = 30
        }

    @Test
    fun `both map room sizes calibrate normal and master floor layouts`() {
        assertEquals(SoloRoomMap(5, 5, 16), SoloRoomMap.calibrate(map(16), "M7"))
        assertEquals(SoloRoomMap(5, 5, 18), SoloRoomMap.calibrate(map(18), "F4"))
        assertEquals(SoloRoomMap(22, 22, 18), SoloRoomMap.calibrate(map(18), "E"))
        assertEquals(SoloRoomMap(22, 11, 18), SoloRoomMap.calibrate(map(18), "F1"))
        assertEquals(SoloRoomMap(11, 11, 18), SoloRoomMap.calibrate(map(18), "M2"))
        assertEquals(SoloRoomMap(11, 11, 18), SoloRoomMap.calibrate(map(18), "F3"))
    }

    @Test
    fun `invalid maps and truncated data cannot generate completion markers`() {
        assertNull(SoloRoomMap.calibrate(map(15), "F7"))
        assertNull(SoloRoomMap.calibrate(ByteArray(10), "F7"))
        assertNull(SoloRoomMap.calibrate(map(16), ""))
        assertEquals(0, SoloRoomMap(22, 22, 18).color(map(18), 35))
        assertEquals(0, SoloRoomMap(5, 5, 16).color(ByteArray(1), 0))
    }

    @Test
    fun `center and corner pixels are distinct and tiles match world grid`() {
        val colors = map(16)
        val calibrated = SoloRoomMap.calibrate(colors, "F7")!!
        colors[13 * 128 + 13] = 34
        assertEquals(34, calibrated.color(colors, 0))
        assertEquals(30, calibrated.color(colors, 0, true))
        assertEquals(0, SoloRoomMap.tile(-185.0, -185.0))
        assertEquals(1, SoloRoomMap.tile(-153.0, -185.0))
        assertEquals(35, SoloRoomMap.tile(-25.0, -25.0))
        assertNull(SoloRoomMap.tile(100.0, 100.0))
    }

    @Test
    fun `variable core blocks normalize without erasing structural blocks`() {
        val air = "minecraft:air".hashCode()
        assertEquals(air, SoloRoomMap.coreToken("minecraft:oak_planks"))
        assertEquals(air, SoloRoomMap.coreToken("minecraft:water"))
        assertEquals(air, SoloRoomMap.coreToken("minecraft:chest"))
        assertEquals("minecraft:stone".hashCode(), SoloRoomMap.coreToken("minecraft:stone"))
    }
}
