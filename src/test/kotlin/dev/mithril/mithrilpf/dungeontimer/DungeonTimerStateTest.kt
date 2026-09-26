package dev.mithril.mithrilpf.dungeontimer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DungeonTimerStateTest {
    private val definitions = DungeonTimerState.loadDefinitions()

    private fun stamp(ticks: Long, millis: Long = ticks * 50) =
        TimerStamp(ticks, millis * 1_000_000)

    private fun state(floor: String = "F7") =
        DungeonTimerState(
            floor,
            definitions[floor] ?: definitions.getValue(floor.replace('M', 'F')),
        )

    private val maxor = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
    private val storm = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private val goldor = "[BOSS] Goldor: Who dares trespass into my domain?"
    private val necron = "[BOSS] Necron: You went further than any human before, congratulations."
    private val end = "   ☠ Defeated Necron in 7m 12s (NEW RECORD!)"

    @Test
    fun `clear boundaries preserve real time and lag adjusted seconds separately`() {
        val s = state()
        s.chat(DungeonTimerState.START, stamp(0))
        val blood = s.chat("[BOSS] The Watcher: Hello.", stamp(200, 14000))
        assertEquals(SplitTime(14000, 200), blood["Blood Open"])
        assertEquals("Watcher Clear", s.current(stamp(210))?.first)
        s.chat(DungeonTimerState.WATCHER_DONE, stamp(400, 24000))
        val entry = s.enterBoss(stamp(420, 25000))
        assertEquals(SplitTime(1000, 20), entry["Portal"])
        assertEquals(SplitTime(25000, 420), entry["Boss Entry"])
        assertTrue(s.enterBoss(stamp(440)).isEmpty())
        s.chat(maxor, stamp(425, 25500))
        assertEquals("Maxor", s.current(stamp(430))?.first)
    }

    @Test
    fun `blood camp resets after rush and portal resets after camp`() {
        val s = state()
        s.chat(DungeonTimerState.START, stamp(0, 0))
        s.chat("[BOSS] The Watcher: Things feel a little more roomy now, eh?", stamp(1120, 56020))
        assertEquals("Watcher Clear" to SplitTime(0, 0), s.current(stamp(1120, 56020)))
        s.chat("[BOSS] The Watcher: This guy looks like a fighter.", stamp(1400, 70000))
        val completed = s.chat(DungeonTimerState.WATCHER_DONE, stamp(2595, 129790))
        assertEquals(56020L, s.completed["Blood Open"]?.realMillis)
        assertEquals(SplitTime(73770, 1475), completed["Watcher Clear"])
        assertEquals("Portal" to SplitTime(0, 0), s.current(stamp(2595, 129790)))
        assertEquals(129790L, s.rows(stamp(2595, 129790))["Boss Entry"]?.realMillis)
    }

    @Test
    fun `split only records at transition and not on abandoning active split`() {
        val s = state()
        s.chat(maxor, stamp(0))
        assertTrue(s.completed.isEmpty())
        val completed = s.chat(storm, stamp(200))
        assertEquals(setOf("Maxor"), completed.keys)
        assertEquals("Storm", s.current(stamp(220))?.first)
        assertFalse("Storm" in s.completed)
        // World change discards this engine without finalizing its current split.
        assertTrue(state().completed.isEmpty())
        assertEquals(setOf("Maxor"), s.completed.keys)
    }

    @Test
    fun `duplicate start and transition messages never reset timers or publish duplicate pbs`() {
        val s = state()
        s.chat(maxor, stamp(0))
        s.chat(maxor, stamp(20))
        assertEquals(200L, s.chat(storm, stamp(200))["Maxor"]?.ticks)
        assertTrue(s.chat(storm, stamp(220)).isEmpty())
        assertEquals(20L, s.current(stamp(220))?.second?.ticks)
    }

    @Test
    fun `f7 and m7 have separate dragon boundaries and finish totals`() {
        for (floor in listOf("F7", "M7")) {
            val s = state(floor)
            s.chat(DungeonTimerState.START, stamp(0))
            s.chat(maxor, stamp(100))
            s.chat(storm, stamp(200))
            s.chat(goldor, stamp(300))
            s.chat("The Core entrance is opening!", stamp(400))
            s.chat(necron, stamp(500))
            val transition = s.chat("[BOSS] Necron: All this, for nothing...", stamp(600))
            assertEquals(floor == "M7", "Necron" in transition)
            assertEquals(if (floor == "M7") "Dragons" else "Necron", s.current(stamp(650))?.first)
            s.chat(end, stamp(700))
            assertTrue(s.ended)
            assertNull(s.current(stamp(800)))
            assertEquals(600L, s.completed["Boss"]?.ticks)
            assertEquals(700L, s.completed["Total"]?.ticks)
            assertEquals(floor == "M7", "Dragons" in s.completed)
            assertTrue(s.chat(end, stamp(900)).isEmpty())
        }
    }

    @Test
    fun `late join cannot invent boss phase records or total from unobserved start`() {
        val s = state()
        assertTrue(s.chat(storm, stamp(200)).isEmpty())
        assertNull(s.current(stamp(250)))
        assertTrue(s.chat(end, stamp(900)).isEmpty())
    }

    @Test
    fun `all noamm floors and normal master fallbacks complete in sequence`() {
        for (floor in (1..7).flatMap { listOf("F$it", "M$it") }) {
            val s = state(floor)
            val splits =
                (definitions[floor] ?: definitions.getValue(floor.replace('M', 'F'))).filter {
                    DungeonTimerState.clean(it.name) != "Boss"
                }
            s.chat(requireNotNull(splits.first().start), stamp(0))
            splits.forEachIndexed { index, split ->
                val endMessage =
                    if (split.end?.startsWith("\\") == true)
                        "[BOSS] Red Livid: Impossible! How did you figure out which one I was?!"
                    else split.end ?: end
                s.chat(endMessage, stamp((index + 1) * 100L))
                assertTrue(
                    DungeonTimerState.clean(split.name) in s.completed,
                    "$floor ${split.name}",
                )
            }
            assertTrue(s.ended, floor)
        }
    }

    @Test
    fun `floor detection distinguishes modes ignores queue and handles formatting`() {
        assertEquals("M7", DungeonTimerState.detectFloor(listOf("§aThe Catacombs §e(M7)")))
        assertEquals("F7", DungeonTimerState.detectFloor(listOf(" ⏣ The Catacombs (F7)")))
        assertEquals("E", DungeonTimerState.detectFloor(listOf("The Catacombs (E)")))
        assertNull(DungeonTimerState.detectFloor(listOf("The Catacombs (F7) Queue")))
        assertNull(DungeonTimerState.detectFloor(listOf("Dungeon Hub")))
    }

    @Test
    fun `countdown repeats all twenty values and ignores theme for color`() {
        assertEquals((19 downTo 0).toList(), (0L..19L).map(DungeonTimerState::countdown))
        assertEquals(19, DungeonTimerState.countdown(20))
        assertEquals(0xFF00FF00.toInt(), DungeonTimerState.countdownColor(19))
        assertEquals(0xFFFF0000.toInt(), DungeonTimerState.countdownColor(0))
    }
}
