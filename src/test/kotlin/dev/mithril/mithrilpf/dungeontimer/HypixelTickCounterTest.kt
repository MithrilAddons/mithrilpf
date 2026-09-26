package dev.mithril.mithrilpf.dungeontimer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HypixelTickCounterTest {
    @Test
    fun `standalone nonzero pings count even with repeated IDs matching Noamm`() {
        val counter = HypixelTickCounter()
        assertTrue(counter.accept(41))
        assertTrue(counter.accept(41))
        assertTrue(counter.accept(41))
        assertTrue(counter.accept(42))
        assertEquals(4, counter.packets)
        assertEquals(0, counter.ignoredBundled)
        assertEquals(0, counter.ignoredZero)
    }

    @Test
    fun `synthetic extra bundled pings do not inflate a blood rush`() {
        val counter = HypixelTickCounter()
        val definitions = DungeonTimerState.loadDefinitions().getValue("F7")
        val state = DungeonTimerState("F7", definitions)
        state.chat(DungeonTimerState.START, TimerStamp(0, 0))
        var ticks = 0L
        for (id in 1..1120) {
            if (counter.accept(id)) ticks++
            if (id <= 212 && counter.accept(-id, bundled = true)) ticks++
        }
        val result = state.chat("[BOSS] The Watcher: Hello.", TimerStamp(ticks, 56_020_000_000L))
        assertEquals(1332, counter.packets)
        assertEquals(212, counter.ignoredBundled)
        assertEquals(SplitTime(56020, 1120), result["Blood Open"])
    }

    @Test
    fun `batches of distinct IDs during lag count fully without wall clock throttling`() {
        val counter = HypixelTickCounter()
        assertEquals(20, (1..20).count { counter.accept(it) })
    }

    @Test
    fun `zero IDs never count while positive and negative standalone IDs do`() {
        val counter = HypixelTickCounter()
        assertFalse(counter.accept(0))
        for (id in listOf(32767, -32768, -1, 1)) {
            assertTrue(counter.accept(id))
            assertFalse(counter.accept(0))
        }
        assertEquals(5, counter.ignoredZero)
    }

    @Test
    fun `world change clears diagnostic state`() {
        val counter = HypixelTickCounter()
        counter.accept(0)
        counter.accept(6, bundled = true)
        counter.accept(5)
        counter.reset()
        assertEquals(0, counter.packets)
        assertEquals(0, counter.ignoredBundled)
        assertEquals(0, counter.ignoredZero)
        assertTrue(counter.accept(5))
    }
}
