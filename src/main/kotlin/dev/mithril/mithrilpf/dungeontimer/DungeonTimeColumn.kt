package dev.mithril.mithrilpf.dungeontimer

/** Align formatted times to the existing table's right edge without resizing it. */
object DungeonTimeColumn {
    fun textX(tableWidth: Int, textWidth: Int): Int = tableWidth - textWidth
}
