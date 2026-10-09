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
