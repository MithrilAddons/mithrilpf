package dev.mithril.mithrilpf.dungeontimer

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DungeonRunHistoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory
        get() = temporary.root.toPath()

    private val player = UUID(0, 1).toString()
    private val party = setOf("Self", "Two", "Three", "Four", "Five")

    private fun stamp(ms: Long, ticks: Long = ms / 50) = TimerStamp(ticks, ms * 1_000_000)

    private fun timer() = DungeonTimerState("E", emptyList())

    private fun completed(lag: Long = 0): DungeonTimerState =
        timer().apply {
            chat(DungeonTimerState.START, stamp(0))
            chat("[BOSS] The Watcher: Hello.", stamp(10_000))
            chat(DungeonTimerState.WATCHER_DONE, stamp(30_000))
            chat("   ☠ Defeated The Watcher in 1m 40s", stamp(100_000 + lag, 2000))
        }

    private fun capture(
        roster: Set<String> = party,
        required: Set<String> = setOf("Blood Open", "Watcher Clear", "Total"),
    ) = DungeonRunCapture(player, "E", 1, roster, "Self", required)

    private fun record(total: Long = 100_000, floor: String = "E", owner: String = player) =
        DungeonRunRecord(
            UUID.randomUUID().toString(),
            owner,
            floor,
            1,
            mapOf(
                "Blood Open" to SplitTime(10_000, 200),
                "Watcher Clear" to SplitTime(20_000, 400),
                "Total" to SplitTime(total, total / 50),
            ),
            mapOf(
                "Blood Open" to SplitTime(10_000, 200),
                "Watcher Clear" to SplitTime(30_000, 600),
                "Total" to SplitTime(total, total / 50),
            ),
            0,
        )

    @Test
    fun `only exactly five players including self at start qualify`() {
        for (roster in
            listOf(emptySet(), party - "Five", party + "Six", party - "Self" + "Other")) {
            assertNull(capture(roster).finish(completed()))
        }
        val mutable = party.toMutableSet()
        val observed = capture(mutable)
        mutable.clear()
        assertNotNull(observed.finish(completed()))
        val short = mutableSetOf("Self")
        val late = capture(short)
        short.addAll(party)
        assertNull(late.finish(completed()))
    }

    @Test
    fun `completion is required and recorded only once`() {
        val run = capture()
        val active = timer().apply { chat(DungeonTimerState.START, stamp(0)) }
        active.chat("You died!", stamp(1000))
        active.chat("EXTRA STATS", stamp(2000))
        assertNull(run.finish(active))
        assertNotNull(run.finish(completed()))
        assertNull(run.finish(completed()))
        assertNull(capture(required = setOf("Maxor", "Total")).finish(completed()))
        assertNull(
            capture().finish(timer().apply { chat("☠ Defeated The Watcher in 1m", stamp(60_000)) })
        )
    }

    @Test
    fun `lag boundary is strictly below twenty seconds and invalid clocks are rejected`() {
        assertNotNull(capture().finish(completed(19_999)))
        assertNull(capture().finish(completed(20_000)))
        assertNull(capture().finish(completed(20_001)))
        assertNotNull(capture().finish(completed(-1000)))
        assertNull(capture().finish(completed(-1001)))
    }

    @Test
    fun `checkpoints are cumulative while split times are individual and duplicates do not change them`() {
        val state = completed()
        assertEquals(SplitTime(20_000, 400), state.completed["Watcher Clear"])
        assertEquals(SplitTime(30_000, 600), state.completedAt["Watcher Clear"])
        val before = state.completedAt.toMap()
        state.chat(DungeonTimerState.WATCHER_DONE, stamp(200_000))
        assertEquals(before, state.completedAt)
        assertEquals(state.completed["Total"], state.completedAt["Total"])
    }

    @Test
    fun `rows grow only as phases start and omit future phases`() {
        val state = timer()
        assertTrue(state.rows(stamp(0)).isEmpty())
        state.chat(DungeonTimerState.START, stamp(0))
        assertEquals(setOf("Blood Open", "Boss Entry", "Total"), state.rows(stamp(1000)).keys)
        state.chat("[BOSS] The Watcher: Hello.", stamp(10_000))
        assertTrue("Watcher Clear" in state.rows(stamp(11_000)))
        assertFalse("Portal" in state.rows(stamp(11_000)))
        state.chat(DungeonTimerState.WATCHER_DONE, stamp(30_000))
        assertTrue("Portal" in state.rows(stamp(31_000)))
    }

    @Test
    fun `estimates use actual checkpoints and extend for an overrunning phase`() {
        val stats = assertNotNull(DungeonRunStatistics.from(listOf(record())))
        val active = timer()
        assertNull(stats.estimate(active, stamp(0)))
        active.chat(DungeonTimerState.START, stamp(0))
        assertEquals(100_000L, stats.estimate(active, stamp(5000))?.realMillis)
        active.chat("[BOSS] The Watcher: Hello.", stamp(20_000))
        assertEquals(110_000L, stats.estimate(active, stamp(25_000))?.realMillis)
        assertEquals(130_000L, stats.estimate(active, stamp(60_000))?.realMillis)
        active.chat(DungeonTimerState.WATCHER_DONE, stamp(60_000))
        assertEquals(130_000L, stats.estimate(active, stamp(61_000))?.realMillis)
        active.chat("☠ Defeated The Watcher in 2m", stamp(120_000))
        assertEquals(SplitTime(120_000, 2400), stats.estimate(active, stamp(150_000)))
    }

    @Test
    fun `median resists outliers and averages middle pair for even counts`() {
        assertNull(DungeonRunStatistics.from(emptyList()))
        val odd =
            assertNotNull(
                DungeonRunStatistics.from(listOf(record(100_000), record(110_000), record(900_000)))
            )
        assertEquals(110_000L, odd.total.realMillis)
        assertEquals(80_000L, odd.remaining["Watcher Clear"]?.realMillis)
        assertEquals(
            105_000L,
            DungeonRunStatistics.from(listOf(record(100_000), record(110_000)))?.total?.realMillis,
        )
    }

    @Test
    fun `m7 fallback uses requested durations without creating history and stays dynamic`() {
        assertNull(DungeonRunStatistics.fallback("F7"))
        assertNull(DungeonRunStatistics.fallback("M6"))
        val fallback = assertNotNull(DungeonRunStatistics.fallback("M7"))
        assertEquals(0, fallback.count)
        assertEquals(SplitTime(296_000, 5920), fallback.total)
        val state = DungeonTimerState("M7", DungeonTimerState.loadDefinitions().getValue("M7"))
        state.chat(DungeonTimerState.START, stamp(0))
        assertEquals(296_000L, fallback.estimate(state, stamp(0))?.realMillis)
        state.chat("[BOSS] The Watcher: Hello.", stamp(10_000))
        assertEquals(286_000L, fallback.estimate(state, stamp(10_000))?.realMillis)
        state.chat(DungeonTimerState.WATCHER_DONE, stamp(74_000))
        state.enterBoss(stamp(78_000))
        assertEquals(286_000L, fallback.estimate(state, stamp(78_000))?.realMillis)
        assertEquals(57_000L, fallback.remaining.getValue("Necron").realMillis)
        assertEquals(0L, fallback.remaining.getValue("Dragons").realMillis)
        val phaseNames =
            listOf(
                "Blood Open",
                "Watcher Clear",
                "Portal",
                "Maxor",
                "Storm",
                "Terminals",
                "Goldor",
                "Necron",
                "Dragons",
            )
        var previous = fallback.total.realMillis
        assertEquals(
            listOf(20L, 64L, 4L, 26L, 46L, 40L, 8L, 31L, 57L),
            phaseNames.map {
                val remaining = fallback.remaining.getValue(it).realMillis
                ((previous - remaining) / 1000).also { previous = remaining }
            },
        )
    }

    @Test
    fun `saved estimates require at least three qualifying runs for that floor`() {
        for (floor in listOf("M7", "F7")) {
            for (count in 0..4) {
                val saved = DungeonRunStatistics.from(List(count) { record(floor = floor) })
                val selected = DungeonRunStatistics.forEstimate(floor, saved)
                if (count >= 3) {
                    assertEquals(saved, selected)
                    assertEquals(100_000L, selected?.total?.realMillis)
                } else {
                    assertEquals(DungeonRunStatistics.fallback(floor), selected)
                }
            }
        }
    }

    @Test
    fun `store keeps slower runs too separates players and floors and reloads`() {
        val store = DungeonRunStore(directory)
        store.load()
        val first = record(floor = "F7")
        assertTrue(store.append(first))
        assertFalse(store.append(first))
        assertFailsWith<IllegalStateException> { store.append(first.copy(startedAt = 2)) }
        store.append(record(110_000, "F7"))
        store.append(record(120_000, "M7"))
        store.append(record(130_000, "F7", UUID(0, 2).toString()))
        val reloaded = DungeonRunStore(directory).apply { load() }.summaries()
        assertEquals(2, reloaded.getValue(player).getValue("F7").count)
        assertEquals(105_000L, reloaded.getValue(player).getValue("F7").total.realMillis)
        assertEquals(1, reloaded.getValue(player).getValue("M7").count)
        assertEquals(2, reloaded.size)
    }

    @Test
    fun `bad history records do not discard or overwrite valid ones`() {
        val store = DungeonRunStore(directory)
        val good = record()
        store.append(good)
        Files.writeString(directory.resolve("bad.json"), "not json")
        Files.writeString(directory.resolve("oversized.json"), " ".repeat(65_537))
        var failures = 0
        store.load { _, _ -> failures++ }
        assertEquals(2, failures)
        assertEquals(1, store.summaries().getValue(player).getValue("E").count)
        assertTrue(Files.exists(directory.resolve("bad.json")))
        assertFailsWith<IllegalArgumentException> { store.append(good.copy(lagMillis = 20_000)) }
        assertEquals(1, store.summaries().getValue(player).getValue("E").count)
    }
}
