package dev.mithril.mithrilpf.ui

import kotlin.test.*

class GamesLayoutTest {
    @Test
    fun `the tightest wide window fits names and ten cells exactly`() {
        // GUI scale 3 on 1080p, or scale 2 at 1280x720.
        val layout = GamesLayout.fit(640, 360)
        assertTrue(layout.wide)
        assertEquals(588, layout.pageWidth)
        assertEquals(148, layout.nameWidth)
        assertEquals(42, layout.cell)
        assertEquals(588, layout.gridWidth)
        assertEquals(layout.pageX, layout.gridX)
        assertEquals(layout.pageX + layout.pageWidth, layout.cellX(9) + layout.cell)
        assertEquals(10, layout.visibleRows(finished = false).coerceAtMost(10))
        assertEquals(10, layout.visibleRows(finished = true))
        assertTrue(layout.rowY(9) + 16 <= layout.cardY)
    }

    @Test
    fun `larger windows widen the names and centre the grid`() {
        val layout = GamesLayout.fit(960, 540)
        assertEquals(200, layout.nameWidth)
        assertEquals(640, layout.gridWidth)
        assertEquals(layout.pageX + (layout.pageWidth - 640) / 2, layout.gridX)
    }

    @Test
    fun `narrow windows use guess numbers and smaller cells`() {
        // GUI scale 3 at 1280x720.
        val layout = GamesLayout.fit(427, 240)
        assertFalse(layout.wide)
        assertEquals(16, layout.nameWidth)
        assertEquals(33, layout.cell)
        assertTrue(layout.gridX + layout.gridWidth <= layout.pageX + layout.pageWidth)
        assertEquals(5, layout.visibleRows(finished = false))
        assertEquals(4, layout.visibleRows(finished = true))
        assertTrue(layout.inputY + 20 <= layout.statusY)
    }

    @Test
    fun `tiny windows still show a row`() {
        val layout = GamesLayout.fit(100, 80)
        assertEquals(1, layout.visibleRows(finished = true))
        assertEquals(20, layout.cell)
    }
}
