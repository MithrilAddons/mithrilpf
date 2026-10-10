package dev.mithril.mithrilpf.ui

/**
 * Games screen geometry in GUI units. Wide windows show item names beside ten clue cells; narrow
 * ones (under 640 wide) swap names for guess numbers and shrink the cells.
 */
data class GamesLayout(val panel: PanelLayout, val wide: Boolean) {
    val pageX
        get() = panel.x + 18

    val pageWidth
        get() = (panel.width - 36).coerceAtLeast(1)

    /** First content line, below the header. */
    val top
        get() = panel.y + 34

    /** End of the content area, above the footer. */
    val bottom
        get() = panel.y + panel.height - 26

    val nameWidth
        get() = if (wide) (pageWidth - COLUMNS * 44).coerceIn(148, 200) else 16

    val cell
        get() = if (wide) 42 else ((pageWidth - 18) / COLUMNS - 2).coerceIn(20, 42)

    val step
        get() = cell + 2

    val gridWidth
        get() = nameWidth + 2 + COLUMNS * step - 2

    val gridX
        get() = pageX + ((pageWidth - gridWidth) / 2).coerceAtLeast(0)

    fun cellX(column: Int) = gridX + nameWidth + 2 + column * step

    val headerY
        get() = top + 24

    val rowsY
        get() = headerY + 11

    fun rowY(index: Int) = rowsY + index * ROW

    val inputY
        get() = bottom - 32

    val statusY
        get() = bottom - 10

    /** Guess rows that fit above the guess box, or above the result card once finished. */
    fun visibleRows(finished: Boolean) =
        (((if (finished) bottom - CARD - 6 else inputY - 4) - rowsY) / ROW).coerceAtLeast(1)

    val cardY
        get() = bottom - CARD

    /** Room for heading text left of the Today and Leaderboard buttons. */
    val headingWidth
        get() = (pageWidth - 152).coerceAtLeast(0)

    val copyWidth
        get() = if (wide) 100 else 80

    /** Whether the answer's name and the round's stats fit side by side on the result card. */
    val cardSplit
        get() = wide || pageWidth - 34 - 110 - 12 - copyWidth - 18 >= 120

    val cardNameWidth
        get() = if (wide) 160 else 110

    /** Where the stats start on the result card, from its left edge. */
    val cardTextX
        get() = if (cardSplit) 34 + cardNameWidth + 12 else 34

    /** Width for the stats, up to the copy button. */
    val cardTextWidth
        get() = (pageWidth - cardTextX - copyWidth - 18).coerceAtLeast(0)

    /** Leaderboard rows that fit between the column headings and the status line. */
    val boardRows
        get() = ((bottom - 14 - rowsY) / ROW).coerceAtLeast(1)

    /**
     * How the leaderboard fills its rows: as many of the [top] rows as fit, then, when your own row
     * is listed separately ([you]), a gap marker and your row, which always show.
     */
    fun board(top: Int, you: Boolean): BoardRows {
        if (!you) return BoardRows(minOf(top, boardRows), gap = false, you = false)
        val room = boardRows - 1
        val gap = top > 0 && room >= 2
        return BoardRows(minOf(top, room - if (gap) 1 else 0), gap, you = true)
    }

    data class BoardRows(val top: Int, val gap: Boolean, val you: Boolean)

    companion object {
        const val COLUMNS = 10
        const val ROW = 18
        const val CARD = 48

        fun fit(width: Int, height: Int): GamesLayout {
            val w = (width - 16).coerceIn(1, 900)
            val h = (height - 16).coerceIn(1, 540)
            return GamesLayout(PanelLayout((width - w) / 2, (height - h) / 2, w, h), width >= 640)
        }
    }
}
