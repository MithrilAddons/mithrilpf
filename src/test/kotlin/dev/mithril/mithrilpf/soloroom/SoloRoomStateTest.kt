package dev.mithril.mithrilpf.soloroom

import dev.mithril.mithrilpf.dungeontimer.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.Json

class SoloRoomStateTest {
    private fun time(tick: Long, ms: Long = tick * 50) = TimerStamp(tick, ms * 1_000_000)

    private fun solo() =
        SoloRoomState("Alice").apply {
            roster(listOf("Alice"))
            start()
        }

    @Test
    fun `unknown roster missing self or missed start never count as solo`() {
        val state = SoloRoomState("Alice")
        state.start()
        assertFalse(state.eligible)
        state.enter("Room", false, false, time(1))
        assertTrue(state.observe("Room", true, true, time(20)).isEmpty())
        val other = SoloRoomState("Alice")
        other.roster(listOf("Alice"))
        assertFalse(other.eligible)
    }

    @Test
    fun `another player including a dead teammate permanently disqualifies the run`() {
        val state = solo()
        state.enter("Room", false, false, time(1))
        state.roster(listOf("Alice", "Bob"))
        state.roster(listOf("Alice"))
        assertFalse(state.eligible)
        assertTrue(state.observe("Room", true, true, time(30)).isEmpty())
        assertEquals("Bob", SoloRoomState.participant("[500] [MVP+] Bob (DEAD)"))
    }

    @Test
    fun `clear and secrets measure independently from first entry and duplicates do not repeat`() {
        val state = solo()
        state.enter("Room", false, false, time(10, 600))
        assertEquals(
            mapOf("Cleared" to SplitTime(2000, 30)),
            state.observe("Room", true, false, time(40, 2600)),
        )
        assertTrue(state.observe("Room", true, false, time(45)).isEmpty())
        assertEquals(
            mapOf("Secrets" to SplitTime(3500, 60)),
            state.observe("Room", true, true, time(70, 4100)),
        )
        assertTrue(state.observe("Room", true, true, time(80)).isEmpty())
    }

    @Test
    fun `switching rooms pauses the previous room and reentry resumes its accumulated time`() {
        val state = solo()
        state.enter("Large Room", false, false, time(10))
        state.enter("Other Room", false, true, time(20))
        state.enter("Large Room", false, false, time(40))
        assertEquals(
            SplitTime(1500, 30),
            state.observe("Large Room", true, false, time(60))["Cleared"],
        )
        assertEquals(
            mapOf("Cleared" to SplitTime(1000, 20)),
            state.observe("Other Room", true, true, time(80)),
        )
    }

    @Test
    fun `clearing the dungeon between room visits does not inflate the secrets split`() {
        val state = solo()
        state.enter("Room", false, false, time(0, 0))
        assertEquals(
            SplitTime(20_000, 380),
            state.observe("Room", true, false, time(380, 20_000))["Cleared"],
        )
        state.leave(time(380, 20_000))
        state.enter("Room", true, false, time(3980, 200_000))
        assertEquals(
            SplitTime(30_000, 570),
            state.observe("Room", true, true, time(4170, 210_000))["Secrets"],
        )
    }

    @Test
    fun `leaving into untracked or unknown areas pauses both clocks and repeated leave is harmless`() {
        val state = solo()
        state.enter("Room", false, false, time(10))
        state.leave(time(30))
        state.leave(time(100))
        state.enter("Room", false, false, time(200))
        assertEquals(
            mapOf("Cleared" to SplitTime(2000, 40), "Secrets" to SplitTime(2000, 40)),
            state.observe("Room", true, true, time(220)),
        )
    }

    @Test
    fun `same room occupancy samples and tiles do not reset or double count time`() {
        val state = solo()
        state.enter("Large Room", false, false, time(10))
        state.enter("Large Room", false, false, time(20))
        state.enter("Large Room", false, false, time(30))
        assertEquals(
            SplitTime(1500, 30),
            state.observe("Large Room", true, false, time(40))["Cleared"],
        )
    }

    @Test
    fun `completion received while outside uses frozen time and does not resume the room`() {
        val state = solo()
        state.enter("Room", false, false, time(10))
        state.leave(time(30))
        assertEquals(SplitTime(1000, 20), state.observe("Room", true, false, time(100))["Cleared"])
        assertEquals(SplitTime(1000, 20), state.observe("Room", true, true, time(200))["Secrets"])
        assertTrue(state.observe("Room", true, true, time(300)).isEmpty())
    }

    @Test
    fun `sub millisecond visit fractions are retained until final rounding`() {
        val state = solo()
        state.enter("Room", false, false, TimerStamp(0, 0))
        state.leave(TimerStamp(1, 600_000))
        state.enter("Room", false, false, TimerStamp(10, 10_000_000))
        assertEquals(
            SplitTime(1, 2),
            state.observe("Room", true, false, TimerStamp(11, 10_600_000))["Cleared"],
        )
    }

    @Test
    fun `green can complete both splits at once but initial completed state cannot manufacture PBs`() {
        val state = solo()
        state.enter("A", false, false, time(1))
        assertEquals(setOf("Cleared", "Secrets"), state.observe("A", true, true, time(5)).keys)
        state.enter("B", true, true, time(10))
        assertTrue(state.observe("B", true, true, time(20)).isEmpty())
        assertTrue(state.observe("Never entered", true, true, time(30)).isEmpty())
    }

    @Test
    fun `cleared on entry can only yield secrets and zero secret rooms yield only clear`() {
        val state = solo()
        state.enter("A", true, false, time(1))
        assertEquals(setOf("Secrets"), state.observe("A", true, true, time(5)).keys)
        state.enter("B", false, true, time(1))
        assertEquals(setOf("Cleared"), state.observe("B", true, false, time(5)).keys)
    }

    @Test
    fun `abandonment disable or zero duration never finalize a split`() {
        val state = solo()
        state.enter("A", false, false, time(1))
        assertTrue(state.observe("A", true, true, time(1)).isEmpty())
        state.enter("B", false, false, time(2))
        state.invalidate()
        assertTrue(state.observe("B", true, true, time(30)).isEmpty())
    }

    @Test
    fun `participant parser ignores NPCs and unrelated tab text`() {
        assertEquals("Alice", SoloRoomState.participant("[615] [MVP++] Alice (Archer L)"))
        assertEquals("Alice", SoloRoomState.participant("[615] Alice (Tank 50)"))
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
            rooms.record("alice", "F7", mapOf("Room · Cleared" to SplitTime(1000, 20)))
            rooms.record(
                "alice",
                "F7",
                mapOf(
                    "Room · Cleared" to SplitTime(1100, 18),
                    "Room · Secrets" to SplitTime(2000, 30),
                ),
            )
            rooms.record("alice", "M7", mapOf("Room · Cleared" to SplitTime(5000, 80)))
            val reload = DungeonPersonalBests(dir.resolve("solo-room-pbs")).apply { load() }
            assertEquals(
                DungeonBest(1000, 18),
                reload.records["alice"]?.get("F7")?.get("Room · Cleared"),
            )
            assertEquals(
                DungeonBest(5000, 80),
                reload.records["alice"]?.get("M7")?.get("Room · Cleared"),
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
