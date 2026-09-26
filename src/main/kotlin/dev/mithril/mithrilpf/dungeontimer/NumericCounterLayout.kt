package dev.mithril.mithrilpf.dungeontimer

/** Unscaled editor bounds and centered text offsets for the single-number HUDs. */
internal data class NumericCounterLayout(val width: Int, val height: Int) {
    fun textOrigin(textWidth: Int, textHeight: Int): Pair<Int, Int> =
        (width - textWidth).coerceAtLeast(0) / 2 to (height - textHeight).coerceAtLeast(0) / 2

    companion object {
        fun measure(textWidth: Int, referenceWidth: Int, lineHeight: Int): NumericCounterLayout =
            NumericCounterLayout(
                maxOf(15, textWidth, referenceWidth),
                maxOf(12, lineHeight + 3),
            )
    }
}
