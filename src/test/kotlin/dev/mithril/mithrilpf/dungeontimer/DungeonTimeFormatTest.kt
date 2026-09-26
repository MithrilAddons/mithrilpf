package dev.mithril.mithrilpf.dungeontimer

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class DungeonTimeFormatTest {
    @Test
    fun `tick text is grey with darker brackets while real time retains PB color`() {
        for (time in listOf(SplitTime(47530, 935), SplitTime(75130, 1397))) {
            val styled = DungeonTimeFormat.styledPair(time, DungeonPbColor.GREEN)
            assertEquals(DungeonTimeFormat.pair(time), styled.string)
            assertEquals(DungeonPbColor.GREEN and 0xFFFFFF, styled.style.color?.value)
            assertEquals(
                listOf(" (", DungeonTimeFormat.ticks(time.ticks), ")"),
                styled.siblings.map { it.string },
            )
            assertEquals(
                listOf(0x777777, 0xAAAAAA, 0x777777),
                styled.siblings.map { it.style.color?.value },
            )
        }
    }

    @Test
    fun `short durations retain hundredths and seconds unit`() {
        assertEquals("0.00s", DungeonTimeFormat.millis(0))
        assertEquals("47.53s", DungeonTimeFormat.millis(47530))
        assertEquals("59.99s", DungeonTimeFormat.millis(59994))
    }

    @Test
    fun `minute and hour boundaries carry correctly including rounding`() {
        assertEquals("1m 0.00s", DungeonTimeFormat.millis(59995))
        assertEquals("1m 0.00s", DungeonTimeFormat.millis(60000))
        assertEquals("1m 15.13s", DungeonTimeFormat.millis(75130))
        assertEquals("2m 0.50s", DungeonTimeFormat.millis(120500))
        assertEquals("1h 0m 0.00s", DungeonTimeFormat.millis(3600000))
        assertEquals("1h 1m 1.23s", DungeonTimeFormat.millis(3661230))
    }

    @Test
    fun `tick durations use twenty ticks per second`() {
        assertEquals("0.05s", DungeonTimeFormat.ticks(1))
        assertEquals("59.95s", DungeonTimeFormat.ticks(1199))
        assertEquals("1m 0.00s", DungeonTimeFormat.ticks(1200))
    }

    @Test
    fun `paired display has real time first and tick time in parentheses without labels`() {
        assertEquals("47.53s (46.75s)", DungeonTimeFormat.pair(SplitTime(47530, 935)))
        assertEquals("1m 15.13s (1m 9.85s)", DungeonTimeFormat.pair(SplitTime(75130, 1397)))
        assertEquals("1m 0.05s (59.95s)", DungeonTimeFormat.pair(SplitTime(60050, 1199)))
    }

    @Test
    fun `format does not depend on system locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1m 15.13s", DungeonTimeFormat.millis(75130))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
