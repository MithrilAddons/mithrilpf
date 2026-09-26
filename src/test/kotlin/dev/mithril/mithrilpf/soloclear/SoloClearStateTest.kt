package dev.mithril.mithrilpf.soloclear

import dev.mithril.mithrilpf.dungeontimer.DungeonPersonalBests
import dev.mithril.mithrilpf.dungeontimer.SplitTime
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SoloClearStateTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun stamp(ticks: Long, realMillis: Long = ticks * 50) =
        TimerStamp(ticks, realMillis * 1_000_000)

    private fun run(floor: String = "M7") =
        SoloClearState("Tester").apply {
            roster(listOf("Tester"))
            begin(floor, stamp(100))
            observe(0, false, stamp(100))
        }

    @Test
    fun `first 300 ends from actual start and ignores future score events`() {
        val state = run()
        assertNull(state.observe(299, false, stamp(200)))
        assertEquals(SplitTime(25_000, 400), state.observe(300, false, stamp(500, 30_000)))
        assertEquals("completed", state.status)
        assertNull(state.observe(301, false, stamp(600)))
    }

    @Test
    fun `no blood boss or run completion required`() {
        for (floor in listOf("F7", "M7")) {
            val state = run(floor)
            assertNotNull(state.observe(305, false, stamp(400)))
            assertEquals(floor, state.floor)
        }
    }

    @Test
    fun `other floors do not qualify but delayed roster preserves start time`() {
        assertFalse(run("M6").active)
        val unknown = SoloClearState("Tester")
        unknown.begin("M7", stamp(100))
        assertEquals("awaiting_roster", unknown.status)
        unknown.observe(0, false, stamp(110))
        unknown.begin("M7", stamp(200))
        unknown.roster(listOf("Tester"))
        assertEquals("tracking", unknown.status)
        assertEquals(400L, unknown.observe(300, false, stamp(500))?.ticks)
    }

    @Test
    fun `early raw dungeon tab before floor detection is retained`() {
        val state = SoloClearState("Tester")
        state.rosterLines(listOf("Players (1)", "[420] [MVP++] Tester (Mage L)"))
        // A custom tab can now contain no recognizable player names at start.
        state.rosterLines(listOf("Custom stats widget"))
        state.begin("F7", stamp(100))
        assertEquals("tracking", state.status)
        state.observe(0, false, stamp(110))
        assertEquals(400L, state.observe(300, false, stamp(500))?.ticks)
    }

    @Test
    fun `late teammate permanently rejects pending run`() {
        val state = SoloClearState("Tester")
        state.begin("M7", stamp(100))
        state.rosterLines(listOf("[420] Tester (Mage L)", "[420] Other (DEAD)"))
        state.rosterLines(listOf("[420] Tester (Mage L)"))
        assertEquals("not_solo", state.status)
        assertNull(state.observe(300, false, stamp(500)))
    }

    @Test
    fun `unconfirmed roster cannot finish or retroactively claim 300`() {
        val state = SoloClearState("Tester")
        state.begin("M7", stamp(100))
        state.observe(0, false, stamp(110))
        assertNull(state.observe(300, false, stamp(500)))
        assertEquals("roster", state.status)
        state.roster(listOf("Tester"))
        assertNull(state.observe(300, false, stamp(600)))
    }

    @Test
    fun `pending roster still checks death disconnect disable and clock`() {
        for (reason in listOf("death", "left", "disabled", "clock")) {
            val state = SoloClearState("Tester")
            state.begin("F7", stamp(100))
            when (reason) {
                "death" -> state.observe(0, true, stamp(110))
                "clock" -> state.observe(0, false, stamp(99))
                else -> state.invalidate(reason)
            }
            state.roster(listOf("Tester"))
            assertEquals(reason, state.status)
            assertNull(state.observe(300, false, stamp(500)))
        }
    }

    @Test
    fun `case only roster changes are not additional players`() {
        val state = run()
        state.roster(listOf("TESTER", "tester"))
        assertEquals("tracking", state.status)
        assertNotNull(state.observe(300, false, stamp(500)))
    }

    @Test
    fun `additional member before or after start permanently invalidates`() {
        for (early in listOf(true, false)) {
            val state = SoloClearState("Tester")
            state.roster(listOf("Tester"))
            if (early) state.roster(listOf("Other"))
            state.begin("F7", stamp(100))
            if (!early) state.roster(listOf("Other"))
            state.roster(listOf("Tester"))
            assertEquals("not_solo", state.status)
            assertNull(state.observe(300, false, stamp(300)))
        }
    }

    @Test
    fun `death even simultaneously with 300 prevents pb`() {
        val state = run()
        assertNull(state.observe(300, true, stamp(200)))
        assertEquals("death", state.status)
        assertNull(state.observe(300, false, stamp(250)))
    }

    @Test
    fun `disabled disconnected or failed attempts cannot resume`() {
        for (reason in listOf("disabled", "left", "death")) {
            val state = run()
            state.invalidate(reason)
            state.begin("M7", stamp(200))
            assertNull(state.observe(300, false, stamp(500)))
            assertEquals(reason, state.status)
        }
    }

    @Test
    fun `missed and repeated start never create shorter record`() {
        assertNull(SoloClearState("Tester").observe(300, false, stamp(300)))
        val state = run()
        state.begin("M7", stamp(400))
        assertEquals(400L, state.observe(300, false, stamp(500))?.ticks)
    }

    @Test
    fun `stale high score unknown score and invalid score do not invent completion`() {
        val state =
            SoloClearState("Tester").apply {
                roster(listOf("Tester"))
                begin("M7", stamp(100))
            }
        for (score in listOf(null, -1, 300, 400, 401)) assertNull(
            state.observe(score, false, stamp(200))
        )
        assertNull(state.observe(200, false, stamp(210)))
        assertNotNull(state.observe(300, false, stamp(220)))
    }

    @Test
    fun `clock reset and runaway attempts are invalidated`() {
        val state = run()
        state.observe(299, false, stamp(200))
        assertNull(state.observe(300, false, stamp(199)))
        assertEquals("clock", state.status)
        val old = run()
        assertNull(old.observe(300, false, stamp(200, 7_300_000)))
        assertEquals("expired", old.status)
    }

    @Test
    fun `records survive restart without mixing floors or players`() {
        val dir = temporary.newFolder("solo-clear-pbs-unverified").toPath()
        val store = DungeonPersonalBests(dir).apply { load() }
        store.record("one", "F7", mapOf("300 Score" to SplitTime(400_000, 7000)))
        store.record("one", "M7", mapOf("300 Score" to SplitTime(500_000, 9000)))
        store.record("two", "F7", mapOf("300 Score" to SplitTime(300_000, 5000)))
        store.record("one", "F7", mapOf("300 Score" to SplitTime(450_000, 6000)))
        val loaded = DungeonPersonalBests(dir).apply { load() }
        assertEquals(6000L, loaded.records["one"]?.get("F7")?.get("300 Score")?.ticks)
        assertEquals(9000L, loaded.records["one"]?.get("M7")?.get("300 Score")?.ticks)
        assertEquals(5000L, loaded.records["two"]?.get("F7")?.get("300 Score")?.ticks)
    }
}
