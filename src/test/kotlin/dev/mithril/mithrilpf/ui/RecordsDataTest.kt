package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.dungeontimer.DungeonBest
import dev.mithril.mithrilpf.dungeontimer.DungeonTimerState
import dev.mithril.mithrilpf.dungeontimer.SplitTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordsDataTest {
    private fun best(ms: Long) = DungeonBest(ms, ms / 50 + 3)

    private val definitions = DungeonTimerState.loadDefinitions()

    private val records =
        mapOf(
            "splits" to
                mapOf(
                    "M7" to
                        mapOf(
                            "Total" to best(312_400),
                            "Blood Open" to best(21_850),
                            "Maxor" to best(33_200),
                            "Retired Split" to best(1_000),
                        ),
                    "F3" to mapOf("Portal" to best(6_350)),
                ),
            "solo" to mapOf("F7" to mapOf("300 Score" to best(281_150))),
            "rooms" to
                mapOf(
                    "M7" to
                        mapOf(
                            "crypt · Clear · Midclear" to best(26_700),
                            "Altar · Clear · Midclear" to best(34_100),
                            "Altar · Clear · Regular" to best(30_200),
                            "Altar · Secrets · Midclear" to best(6_700),
                            "Altar · Secrets · Regular" to best(5_450),
                            "Altar · Total" to best(40_700),
                            "Boulder · Clear" to best(18_400),
                            "Old · Cleared" to best(50_000),
                        ),
                    "M1" to mapOf("Old · Cleared" to best(50_000)),
                ),
        )

    private val data = RecordsData(records, definitions)

    @Test
    fun `splits run in order with the floor's own splits, Total and Solo 300 last`() {
        val rows = data.splits("M7")
        val m7 = definitions.getValue("M7").map { DungeonTimerState.clean(it.name) }
        assertEquals(
            listOf("Blood Open", "Watcher Clear", "Portal", "Boss Entry") +
                m7 +
                listOf("Retired Split", "Total", "300 Score"),
            rows.map { it.name },
        )
        assertEquals(best(21_850), rows.first().best)
        assertNull(rows[1].best)
        assertEquals(SplitKind.TOTAL, rows[rows.size - 2].kind)
        assertEquals(SplitKind.SOLO, rows.last().kind)
        assertNull(rows.last().best)
        assertEquals(best(281_150), data.splits("F7").last().best)
    }

    @Test
    fun `solo 300 only shows on floors that have one`() {
        assertEquals("Total", data.splits("F3").last().name)
        assertEquals(best(6_350), data.splits("F3").first { it.name == "Portal" }.best)
        assertEquals("Total", data.splits("E").last().name)
    }

    @Test
    fun `rooms combine their PBs and old style-less clears are left out`() {
        val rooms = data.rooms("M7")
        assertEquals(listOf("Altar", "Boulder", "crypt"), rooms.map { it.name })
        val altar = rooms[0]
        assertEquals(best(34_100), altar.midclear)
        assertEquals(best(30_200), altar.regular)
        assertEquals(best(6_700), altar.secretsMid)
        assertEquals(best(5_450), altar.secretsReg)
        assertEquals(best(40_700), altar.total)
        assertFalse(altar.noSecrets)
        assertEquals(RoomRow("Boulder", regular = best(18_400), noSecrets = true), rooms[1])
        assertEquals(RoomRow("crypt", midclear = best(26_700)), rooms[2])
    }

    @Test
    fun `room search matches part of a name in any case`() {
        assertEquals(listOf("Altar"), data.rooms("M7", " alt ").map { it.name })
        assertEquals(listOf("Altar", "crypt"), data.rooms("M7", "T").map { it.name })
        assertTrue(data.rooms("M7", "waterfall").isEmpty())
        assertTrue(data.rooms("F7").isEmpty())
    }

    @Test
    fun `a floor has PBs when anything but an old room clear is saved`() {
        assertTrue(data.hasPbs("M7"))
        assertTrue(data.hasPbs("F3"))
        assertTrue(data.hasPbs("F7"))
        assertFalse(data.hasPbs("M1"))
        assertFalse(data.hasPbs("E"))
        assertFalse(RecordsData(emptyMap(), definitions).hasPbs("M7"))
    }

    @Test
    fun `rates and times come from saved bests`() {
        assertEquals("9.0/min", RecordsData.rate(6_700))
        assertEquals("60000.0/min", RecordsData.rate(0))
        assertEquals(SplitTime(30_200, 607), RecordsData.time(best(30_200)))
        assertEquals(best(30_200), data.best("rooms", "M7", "Altar · Clear · Regular"))
        assertNull(data.best("solo", "M7", RecordsData.SOLO))
    }

    @Test
    fun `room tables show both styles only when six columns fit`() {
        val wide = RoomColumns.fit(728)
        assertTrue(wide.both)
        assertEquals(listOf(424, 490, 572, 654, 720), wide.rights)
        assertEquals(350, wide.nameWidth)
        val narrow = RoomColumns.fit(268)
        assertFalse(narrow.both)
        assertEquals(listOf(142, 200, 260), narrow.rights)
        assertEquals(74, narrow.nameWidth)
        assertFalse(RoomColumns.fit(RoomColumns.MIN_BOTH - 1).both)
        assertTrue(RoomColumns.fit(RoomColumns.MIN_BOTH).both)
        assertEquals(0, RoomColumns.fit(100).nameWidth)
    }

    @Test
    fun `the floor list puts Entrance on top and pairs each F floor with its M floor`() {
        val picker = FloorPicker(100, 50)
        assertEquals(FloorPicker.Cell(104, 54, 140, 16), picker.cell("E"))
        assertEquals(FloorPicker.Cell(104, 90, 68, 16), picker.cell("F1"))
        assertEquals(FloorPicker.Cell(176, 90, 68, 16), picker.cell("M1"))
        assertEquals(FloorPicker.Cell(176, 186, 68, 16), picker.cell("M7"))
        assertNull(picker.cell("F8"))
        assertEquals("E", picker.floorAt(104.0, 54.0))
        assertEquals("F7", picker.floorAt(171.9, 201.9))
        assertEquals("M4", picker.floorAt(200.0, 140.0))
        assertNull(picker.floorAt(173.0, 140.0))
        assertNull(picker.floorAt(200.0, 80.0))
        assertTrue(picker.contains(100.0, 50.0))
        assertFalse(picker.contains(100.0 + FloorPicker.WIDTH, 50.0))
        assertEquals(15, FloorPicker.FLOORS.size)
    }

    @Test
    fun `the floor list opens above the button when there is no room below`() {
        assertEquals(FloorPicker(10, 42), FloorPicker.below(10, 20, 20, 400))
        val above = FloorPicker.below(10, 200, 20, 240)
        assertEquals(200 - 2 - FloorPicker.HEIGHT, above.y)
        assertEquals(0, FloorPicker.below(10, 60, 20, 100).y)
    }
}
