package dev.mithril.mithrilpf.ui

/** [text] shortened with an ellipsis until it fits [width], as [measure] counts width. */
internal fun ellipsize(text: String, width: Int, measure: (String) -> Int): String {
    if (measure(text) <= width) return text
    var end = text.length
    while (end > 0 && measure(text.substring(0, end).trimEnd() + "…") > width) end--
    return if (end == 0) "" else text.substring(0, end).trimEnd() + "…"
}

/**
 * Where the finder HUD can start below vanilla's boss bars: each bar takes 19 units from y 12, and
 * vanilla stops drawing more once they reach a third of the screen height.
 */
internal fun finderHudTop(bossBars: Int, screenHeight: Int): Int {
    var y = 12
    for (bar in 1..bossBars) {
        y += 19
        if (y >= screenHeight / 3) break
    }
    return y - 6
}
