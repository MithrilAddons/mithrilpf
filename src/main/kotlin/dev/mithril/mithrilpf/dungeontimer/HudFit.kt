package dev.mithril.mithrilpf.dungeontimer

/**
 * A HUD element's configured [scale], shrunk so its [width] x [height] at scale 1 fits the screen.
 * A live table can be far taller than the editor's sample, or the GUI scale can change later.
 */
internal fun fittedScale(
    scale: Double,
    width: Int,
    height: Int,
    screenWidth: Int,
    screenHeight: Int,
): Double =
    minOf(
        scale,
        screenWidth.toDouble() / width.coerceAtLeast(1),
        screenHeight.toDouble() / height.coerceAtLeast(1),
    )
