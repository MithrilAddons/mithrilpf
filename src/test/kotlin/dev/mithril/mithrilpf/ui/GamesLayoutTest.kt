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
    fun `a finished round's rows stay clear of the Load new item button`() {
        for ((width, height) in listOf(640 to 360, 960 to 540, 427 to 240)) {
            val layout = GamesLayout.fit(width, height)
            val lastRowEnd = layout.rowY(layout.visibleRows(finished = true) - 1) + 16
            assertTrue(lastRowEnd <= layout.inputY, "$width x $height")
            assertTrue(layout.inputY + 20 <= layout.statusY, "$width x $height")
        }
    }

    @Test
    fun `the result card's stats always have room beside the copy button`() {
        // GUI scale 4 at 1280x960 gives the narrowest window, 320 wide.
        val smallest = GamesLayout.fit(320, 240)
        assertFalse(smallest.cardSplit)
        assertEquals(34, smallest.cardTextX)
        assertEquals(136, smallest.cardTextWidth)
        val narrow = GamesLayout.fit(427, 240)
        assertTrue(narrow.cardSplit)
        assertEquals(121, narrow.cardTextWidth)
        for ((width, height) in listOf(320 to 240, 427 to 240, 640 to 360, 960 to 540)) {
            val layout = GamesLayout.fit(width, height)
            assertTrue(layout.cardTextWidth >= 120, "$width x $height")
            assertTrue(
                layout.cardTextX + layout.cardTextWidth + layout.copyWidth + 18 <= layout.pageWidth
            )
        }
    }

    @Test
    fun `the leaderboard keeps your own row on screen and above the status line`() {
        for ((width, height) in listOf(320 to 240, 533 to 300, 640 to 333, 640 to 360)) {
            val layout = GamesLayout.fit(width, height)
            val rows = layout.board(10, you = true)
            assertTrue(rows.you)
            val used = rows.top + (if (rows.gap) 1 else 0) + 1
            assertTrue(used <= layout.boardRows, "$width x $height")
            assertTrue(layout.rowY(used - 1) + 16 <= layout.statusY, "$width x $height")
            // The heading stops before the Today and Leaderboard buttons.
            assertEquals(layout.pageWidth - 152, layout.headingWidth)
        }
        val smallest = GamesLayout.fit(320, 240)
        assertEquals(6, smallest.boardRows)
        assertEquals(GamesLayout.BoardRows(4, gap = true, you = true), smallest.board(5, true))
        assertEquals(GamesLayout.BoardRows(6, gap = false, you = false), smallest.board(10, false))
        assertEquals(GamesLayout.BoardRows(0, gap = false, you = true), smallest.board(0, true))
        val tiny = GamesLayout.fit(100, 80)
        assertEquals(1, tiny.boardRows)
        assertEquals(GamesLayout.BoardRows(0, gap = false, you = true), tiny.board(3, true))
        assertEquals(0, tiny.headingWidth)
    }

    @Test
    fun `tiny windows still show a row`() {
        val layout = GamesLayout.fit(100, 80)
        assertEquals(1, layout.visibleRows(finished = true))
        assertEquals(20, layout.cell)
    }
}
