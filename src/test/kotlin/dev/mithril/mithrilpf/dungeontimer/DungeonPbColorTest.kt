package dev.mithril.mithrilpf.dungeontimer

import kotlin.test.Test
import kotlin.test.assertEquals

class DungeonPbColorTest {
    @Test
    fun `all split thresholds are inclusive and change on the next tick`() {
        val thresholds =
            mapOf(
                "Blood Open" to (5 to 10),
                "Watcher Clear" to (4 to 8),
                "Portal" to (1 to 1),
                "Maxor" to (1 to 2),
                "Storm" to (1 to 2),
                "Terminals" to (3 to 7),
                "Goldor" to (1 to 3),
                "Necron" to (1 to 2),
                "Dragons" to (2 to 4),
                "Boss Entry" to (7 to 15),
                "Boss" to (7 to 15),
                "Total" to (12 to 25),
                "Terracottas" to (1 to 3),
                "First Kill" to (1 to 3),
                "Unlisted Split" to (1 to 3),
            )
        val best = 1000L
        for ((split, limits) in thresholds) {
            val (green, yellow) = limits
            assertEquals(DungeonPbColor.GREEN, DungeonPbColor.color(split, best - 200, best), split)
            assertEquals(DungeonPbColor.GREEN, DungeonPbColor.color(split, best, best), split)
            assertEquals(
                DungeonPbColor.GREEN,
                DungeonPbColor.color(split, best + green * 20, best),
                split,
            )
            assertEquals(
                if (green == yellow) DungeonPbColor.RED else DungeonPbColor.YELLOW,
                DungeonPbColor.color(split, best + green * 20 + 1, best),
                split,
            )
            assertEquals(
                if (green == yellow) DungeonPbColor.GREEN else DungeonPbColor.YELLOW,
                DungeonPbColor.color(split, best + yellow * 20, best),
                split,
            )
            assertEquals(
                DungeonPbColor.RED,
                DungeonPbColor.color(split, best + yellow * 20 + 1, best),
                split,
            )
        }
    }

    @Test
    fun `missing PB is red even at the start of a split`() {
        assertEquals(DungeonPbColor.RED, DungeonPbColor.color("Maxor", 10000, null))
        assertEquals(DungeonPbColor.RED, DungeonPbColor.color("Blood Open", 0, null))
    }

    @Test
    fun `lagged real time does not influence color`() {
        val normal = SplitTime(32000, 640)
        val lagged = SplitTime(90000, 640)
        val best = DungeonBest(30000, 600)
        assertEquals(DungeonPbColor.YELLOW, DungeonPbColor.color("Maxor", normal.ticks, best.ticks))
        assertEquals(DungeonPbColor.YELLOW, DungeonPbColor.color("Maxor", lagged.ticks, best.ticks))
    }
}
