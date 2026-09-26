package dev.mithril.mithrilpf.dungeontimer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DungeonTimeColumnTest {
    @Test
    fun `short and long times end at the table right edge without widening it`() {
        val labelWidth = 72
        val columnWidth = 121
        val left = labelWidth + 8
        // The footer can make the table wider than the split columns alone.
        for (right in listOf(left + columnWidth, left + columnWidth + 60)) {
            for (textWidth in listOf(0, 1, 30, 60, 120, 121)) {
                val x = DungeonTimeColumn.textX(right, textWidth)
                assertTrue(x >= left)
                assertEquals(right, x + textWidth)
            }
        }
    }
}
