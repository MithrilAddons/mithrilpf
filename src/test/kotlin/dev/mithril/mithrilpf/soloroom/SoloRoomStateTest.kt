package dev.mithril.mithrilpf.soloroom

import dev.mithril.mithrilpf.dungeontimer.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.Json

class SoloRoomStateTest {
    private fun time(tick: Long, ms: Long = tick * 50) = TimerStamp(tick, ms * 1_000_000)

    private fun span(ticks: Long) = SplitTime(ticks * 50, ticks)

    private fun state() =
        SoloRoomState("Alice").apply {
            roster(listOf("[615] Alice (Mage 50)", "[500] Bob (Tank 40)"))
            start()
        }

    private fun SoloRoomState.walk(room: String, tick: Long, secretless: Boolean = false) =
        enter(room, true, false, secretless, time(tick))

    @Test
    fun `nothing is tracked before the run starts or after it is invalidated`() {
        val idle = SoloRoomState("Alice")
        assertFalse(idle.eligible)
        assertTrue(idle.walk("Room", 1).isEmpty())
        idle.secrets("Room", 0, 2, time(1))
        assertTrue(idle.observe("Room", true, time(20)).isEmpty())
        val state = state()
        state.walk("Room", 1)
        state.invalidate()
        assertFalse(state.eligible)
        assertTrue(state.observe("Room", true, time(30)).isEmpty())
        assertTrue(state.finishAll().isEmpty())
    }

    @Test
    fun `midclear needs half the secrets rounded down, between one and three`() {
        assertEquals(
            listOf(1, 1, 1, 2, 2, 3, 3, 3, 3, 3),
            (1..10).map(SoloRoomState::midclearThreshold),
        )
    }

    @Test
    fun `the roster keeps living teammates and drops you and the dead`() {
        val state = SoloRoomState("Alice")
        state.roster(
            listOf(
                "[615] Alice (Mage 50)",
                "[500] Bob (Tank 40)",
                "[400] Carol (DEAD)",
                "[NPC] Mort",
            )
        )
        assertEquals(setOf("Bob"), state.teammates)
    }

    @Test
    fun `a regular room reports its clear at white and its secrets on walking out`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 4, time(11))
        val clear = state.observe("Room", false, time(40))
        assertEquals(listOf(RoomResult("Room", RoomStyle.REGULAR, clear = span(30))), clear)
        state.secrets("Room", 1, 4, time(50))
        state.secrets("Room", 2, 4, time(60))
        assertTrue(state.observe("Room", false, time(61)).isEmpty())
        val finished = state.walk("Next", 70).single()
        assertEquals(RoomStyle.REGULAR, finished.style)
        assertNull(finished.clear)
        assertNull(finished.total)
        val secrets = assertNotNull(finished.secrets)
        assertEquals(RoomSecrets(2, 4, 2, span(20)), secrets)
        assertEquals(SplitTime(500, 10), secrets.perSecret)
        assertEquals(120.0, secrets.perMinute)
    }

    @Test
    fun `a midclear reports clear secrets and total together once finished`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 4, time(10))
        state.secrets("Room", 1, 4, time(20))
        state.secrets("Room", 2, 4, time(30))
        assertTrue(state.observe("Room", false, time(40)).isEmpty())
        state.secrets("Room", 3, 4, time(50))
        val result = state.walk("Next", 60).single()
        assertEquals(
            RoomResult("Room", RoomStyle.MIDCLEAR, span(30), RoomSecrets(3, 4, 3, span(40)), null),
            result,
        )
    }

    @Test
    fun `a midclear finishing its secrets before white ends its total at white`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 2, time(10))
        state.secrets("Room", 1, 2, time(20))
        state.secrets("Room", 2, 2, time(30))
        val result = state.observe("Room", true, time(40)).single()
        assertEquals(RoomStyle.MIDCLEAR, result.style)
        assertEquals(span(30), result.clear)
        assertEquals(RoomSecrets(2, 2, 2, span(20)), result.secrets)
        assertEquals(span(30), result.total)
        assertTrue(state.walk("Next", 50).isEmpty())
    }

    @Test
    fun `total needs every secret and finishes the room as soon as the last is found`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 2, time(10))
        assertEquals(span(10), state.observe("Room", false, time(20)).single().clear)
        state.secrets("Room", 1, 2, time(30))
        state.secrets("Room", 2, 2, time(40))
        val result = state.observe("Room", false, time(41)).single()
        assertEquals(RoomSecrets(2, 2, 2, span(20)), result.secrets)
        assertEquals(span(30), result.total)
    }

    @Test
    fun `a green map marker counts the last secret before the action bar shows it`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 1, time(10))
        // White and green together means every secret was found during the clear.
        assertEquals(
            RoomResult(
                "Room",
                RoomStyle.MIDCLEAR,
                span(20),
                RoomSecrets(1, 1, 1, span(20)),
                span(20),
            ),
            state.observe("Room", true, time(30)).single(),
        )
        assertTrue(state.walk("Other", 40).isEmpty())
    }

    @Test
    fun `a teammate in the room before white voids the clear but not later secrets`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 2, time(10))
        state.others(setOf("Room"))
        state.others(emptySet())
        assertTrue(state.observe("Room", false, time(20)).isEmpty())
        state.secrets("Room", 1, 2, time(30))
        val result = state.walk("Next", 40).single()
        assertEquals(RoomSecrets(1, 2, 1, span(10)), result.secrets)
        assertNull(result.total)
    }

    @Test
    fun `a teammate who was there before you arrived voids the clear too`() {
        val state = state()
        state.others(setOf("Room"))
        state.others(emptySet())
        state.walk("Room", 10, secretless = true)
        assertTrue(state.observe("Room", false, time(20)).isEmpty())
    }

    @Test
    fun `a teammate present when a secret is found voids the secrets only`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 2, time(10))
        assertEquals(span(10), state.observe("Room", false, time(20)).single().clear)
        state.others(setOf("Room"))
        state.secrets("Room", 1, 2, time(30))
        assertTrue(state.walk("Next", 40).isEmpty())
    }

    @Test
    fun `a teammate after white or in another room changes nothing`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 2, time(10))
        state.others(setOf("Elsewhere"))
        assertEquals(span(10), state.observe("Room", false, time(20)).single().clear)
        state.others(setOf("Room"))
        state.others(emptySet())
        state.secrets("Room", 1, 2, time(30))
        state.secrets("Room", 2, 2, time(40))
        val result = state.observe("Room", false, time(41)).single()
        assertEquals(span(30), result.total)
    }

    @Test
    fun `a counter that did not start at zero leaves no style and no results`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 1, 3, time(10))
        assertTrue(state.observe("Room", false, time(20)).isEmpty())
        state.secrets("Room", 3, 3, time(30))
        assertTrue(state.observe("Room", true, time(31)).isEmpty())
    }

    @Test
    fun `a room never read is unknown and readings elsewhere are ignored`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Other", 0, 3, time(10))
        assertTrue(state.observe("Room", false, time(20)).isEmpty())
        assertTrue(state.finishAll().isEmpty())
    }

    @Test
    fun `time away from the room is not counted and later secrets recount the room`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 4, time(10))
        assertEquals(span(10), state.observe("Room", false, time(20)).single().clear)
        state.leave(time(25))
        state.leave(time(30))
        state.walk("Room", 100)
        state.secrets("Room", 1, 4, time(105))
        assertEquals(RoomSecrets(1, 4, 1, span(10)), state.walk("Next", 110).single().secrets)
        state.walk("Room", 200)
        state.secrets("Room", 2, 4, time(210))
        assertEquals(RoomSecrets(2, 4, 2, span(25)), state.walk("Next", 220).single().secrets)
        assertTrue(state.walk("Room", 230).isEmpty())
        assertTrue(state.walk("Next", 240).isEmpty())
        assertTrue(state.finishAll().isEmpty())
    }

    @Test
    fun `untracked rooms finish the room you came from without an attempt of their own`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 2, time(10))
        state.observe("Room", false, time(20))
        state.secrets("Room", 1, 2, time(30))
        assertEquals(1, state.enter("Fairy", false, false, true, time(40)).size)
        assertTrue(state.observe("Fairy", true, time(50)).isEmpty())
    }

    @Test
    fun `boss entry finishes every white room and leaves the rest`() {
        val state = state()
        state.walk("Open", 1)
        state.secrets("Open", 0, 2, time(1))
        state.walk("A", 10)
        state.secrets("A", 0, 2, time(10))
        state.observe("A", false, time(20))
        state.secrets("A", 1, 2, time(30))
        val results = state.finishAll()
        assertEquals(listOf("A"), results.map { it.room })
        assertTrue(state.finishAll().isEmpty())
    }

    @Test
    fun `a room already done on entry and a room without secrets`() {
        val state = state()
        state.enter("Done", true, true, false, time(1))
        assertTrue(state.observe("Done", true, time(20)).isEmpty())
        state.walk("Plain", 30, secretless = true)
        assertEquals(
            listOf(RoomResult("Plain", null, clear = span(10))),
            state.observe("Plain", false, time(40)),
        )
        assertTrue(state.observe("Plain", false, time(50)).isEmpty())
    }

    @Test
    fun `zero length clears and same room samples do not count`() {
        val state = state()
        state.walk("Room", 10, secretless = true)
        state.walk("Room", 20, secretless = true)
        assertTrue(state.observe("Room", false, time(10)).isEmpty())
    }

    @Test
    fun `sub millisecond visit fractions are retained until final rounding`() {
        val state = state()
        state.enter("Room", true, false, true, TimerStamp(0, 0))
        state.leave(TimerStamp(1, 600_000))
        state.enter("Room", true, false, true, TimerStamp(10, 10_000_000))
        assertEquals(
            SplitTime(1, 2),
            state.observe("Room", false, TimerStamp(11, 10_600_000)).single().clear,
        )
    }

    @Test
    fun `a tick reads your tile, every room marker and where teammates stand`() {
        val waterfall = SoloRoomDefinition("Waterfall", "NORMAL", emptyList(), 2)
        val fairy = SoloRoomDefinition("Fairy", "FAIRY", emptyList(), 0)
        val rooms = listOf(waterfall, waterfall, fairy, null)
        val colors = mutableMapOf(0 to 63, 1 to 63, 2 to 82)
        val color = { tile: Int -> colors[tile] ?: 0 }
        val state = state()
        assertTrue(state.tick(rooms, 0, color, emptySet(), time(10)).isEmpty())
        state.counter(waterfall, "§7 0/2 Secrets", time(10))
        state.counter(waterfall, "§7 0/5 Secrets", time(11))
        state.counter(null, "§7 1/2 Secrets", time(11))
        state.counter(waterfall, "no counter here", time(11))
        colors[0] = 34
        colors[1] = 34
        assertEquals(span(10), state.tick(rooms, 1, color, emptySet(), time(20)).single().clear)
        state.counter(waterfall, "§7 1/2 Secrets", time(30))
        // A teammate on the room's other tile at the next secret voids the secrets.
        state.tick(rooms, 1, color, setOf(0, 3), time(35))
        state.counter(waterfall, "§7 2/2 Secrets", time(40))
        colors[0] = 30
        colors[1] = 30
        assertTrue(state.tick(rooms, 1, color, emptySet(), time(45)).isEmpty())
        assertTrue(state.tick(rooms, 2, color, emptySet(), time(50)).isEmpty())
        assertTrue(state.tick(rooms, 3, color, emptySet(), time(60)).isEmpty())
        assertTrue(SoloRoomState("Alice").tick(rooms, 0, color, emptySet(), time(1)).isEmpty())
    }

    @Test
    fun `a regular room can be cleared, left and revisited for its secrets`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 3, time(10))
        assertEquals(span(10), state.observe("Room", false, time(20)).single().clear)
        assertTrue(state.walk("Next", 30).isEmpty())
        state.walk("Room", 100)
        state.secrets("Room", 1, 3, time(110))
        state.secrets("Room", 2, 3, time(120))
        // Only time inside the room counts: 10 ticks after white on the first visit, 20 now.
        assertEquals(RoomSecrets(2, 3, 2, span(30)), state.walk("Next", 130).single().secrets)
        state.walk("Room", 200)
        state.secrets("Room", 3, 3, time(210))
        val full = state.observe("Room", false, time(211)).single()
        assertEquals(RoomSecrets(3, 3, 3, span(50)), full.secrets)
        assertEquals(span(60), full.total)
    }

    @Test
    fun `a midclear revisited for more secrets is reported again, recounted`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 4, time(10))
        state.secrets("Room", 1, 4, time(20))
        state.secrets("Room", 2, 4, time(30))
        assertTrue(state.observe("Room", false, time(40)).isEmpty())
        assertEquals(
            RoomResult("Room", RoomStyle.MIDCLEAR, span(30), RoomSecrets(2, 4, 2, span(20))),
            state.walk("Next", 50).single(),
        )
        state.walk("Room", 100)
        state.secrets("Room", 3, 4, time(110))
        assertEquals(
            RoomResult("Room", RoomStyle.MIDCLEAR, span(30), RoomSecrets(3, 4, 3, span(50))),
            state.walk("Next", 120).single(),
        )
    }

    @Test
    fun `a recount with a teammate at a new secret withdraws the earlier secrets`() {
        val state = state()
        state.walk("Room", 10)
        state.secrets("Room", 0, 4, time(10))
        state.observe("Room", false, time(20))
        state.secrets("Room", 1, 4, time(30))
        assertNotNull(state.walk("Next", 40).single().secrets)
        state.walk("Room", 100)
        state.others(setOf("Room"))
        state.secrets("Room", 2, 4, time(110))
        assertEquals(RoomResult("Room", RoomStyle.REGULAR), state.walk("Next", 120).single())
    }

    @Test
    fun `a room turning white while you are outside keeps its time frozen`() {
        val state = state()
        state.walk("Room", 10, secretless = true)
        state.leave(time(30))
        assertEquals(span(20), state.observe("Room", false, time(100)).single().clear)
    }

    @Test
    fun `regular rooms with no secrets after white report only their clear`() {
        val state = state()
        state.enter("Fairy", false, false, true, time(1))
        state.walk("Empty", 10)
        state.secrets("Empty", 0, 4, time(10))
        assertEquals(1, state.observe("Empty", false, time(20)).size)
        assertTrue(state.walk("Early", 30).isEmpty())
        state.secrets("Early", 0, 4, time(30))
        state.secrets("Early", 1, 4, time(35))
        assertEquals(RoomStyle.REGULAR, state.observe("Early", false, time(40)).single().style)
        assertTrue(state.walk("Next", 50).isEmpty())
    }

    @Test
    fun `clears need both a real and a tick duration`() {
        val state = state()
        state.enter("A", true, false, true, TimerStamp(0, 0))
        assertTrue(state.observe("A", false, TimerStamp(1, 0)).isEmpty())
        state.enter("B", true, false, true, TimerStamp(1, 0))
        assertTrue(state.observe("B", false, TimerStamp(1, 5_000_000)).isEmpty())
    }

    @Test
    fun `a tick without a map marker, an unopened tile and a green room without secrets`() {
        val plain = SoloRoomDefinition("Plain", "NORMAL", emptyList(), 0)
        val other = SoloRoomDefinition("Other", "NORMAL", emptyList(), 0)
        val rooms = listOf(plain, other)
        val colors = mutableMapOf(0 to 0, 1 to 18)
        val state = state()
        state.tick(rooms, 0, { colors[it] ?: 0 }, emptySet(), time(1))
        state.tick(rooms, 1, { colors[it] ?: 0 }, emptySet(), time(2))
        colors[0] = 63
        state.tick(rooms, 0, { colors[it] ?: 0 }, emptySet(), time(10))
        colors[0] = 30
        assertEquals(
            listOf(RoomResult("Plain", null, clear = span(10))),
            state.tick(rooms, 0, { colors[it] ?: 0 }, emptySet(), time(20)),
        )
    }

    @Test
    fun `participant parser ignores NPCs and unrelated tab text`() {
        assertEquals("Alice", SoloRoomState.participant("[615] [MVP++] Alice (Archer L)"))
        assertEquals("Alice", SoloRoomState.participant("[615] Alice (Tank 50)"))
        assertEquals("Bob", SoloRoomState.participant("[500] [MVP+] Bob (DEAD)"))
        assertNull(SoloRoomState.participant("[NPC] Mort"))
        assertNull(SoloRoomState.participant("Party: Alice Bob"))
        assertNull(SoloRoomState.participant("[615] Alice (Garden)"))
    }

    @Test
    fun `room PBs persist separately from existing dungeon splits and separate floors and clocks`() {
        val dir = Files.createTempDirectory("solo-pbs-test")
        try {
            val original = DungeonPersonalBests(dir).apply { load() }
            original.record("alice", "F7", mapOf("Boss" to SplitTime(5000, 100)))
            val before = Files.readAllBytes(dir.resolve("dungeon-pbs.dat"))
            val rooms = DungeonPersonalBests(dir.resolve("solo-room-pbs")).apply { load() }
            rooms.record("alice", "F7", mapOf("Room · Clear · Regular" to SplitTime(1000, 20)))
            rooms.record(
                "alice",
                "F7",
                mapOf(
                    "Room · Clear · Regular" to SplitTime(1100, 18),
                    "Room · Total" to SplitTime(2000, 30),
                ),
            )
            rooms.record("alice", "M7", mapOf("Room · Clear · Regular" to SplitTime(5000, 80)))
            val reload = DungeonPersonalBests(dir.resolve("solo-room-pbs")).apply { load() }
            assertEquals(
                DungeonBest(1000, 18),
                reload.records["alice"]?.get("F7")?.get("Room · Clear · Regular"),
            )
            assertEquals(
                DungeonBest(5000, 80),
                reload.records["alice"]?.get("M7")?.get("Room · Clear · Regular"),
            )
            assertContentEquals(before, Files.readAllBytes(dir.resolve("dungeon-pbs.dat")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `bundled room dictionary is complete and has no conflicting core identifiers`() {
        val data =
            Json.decodeFromString<List<SoloRoomDefinition>>(
                javaClass
                    .getResourceAsStream("/assets/mithrilpf/data/solo-rooms.json")!!
                    .reader()
                    .readText()
            )
        assertEquals(140, data.size)
        assertTrue(data.all { it.secrets >= 0 && it.name.isNotBlank() })
        val cores =
            data
                .flatMap { room -> room.cores.map { it to room.name } }
                .groupBy({ it.first }, { it.second })
        assertTrue(cores.values.all { it.distinct().size == 1 })
        assertFalse(data.first { it.type == "ENTRANCE" }.tracked)
        assertTrue(data.first { it.type == "PUZZLE" }.tracked)
    }
}
