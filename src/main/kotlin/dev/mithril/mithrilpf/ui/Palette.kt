package dev.mithril.mithrilpf.ui

/** Shared visual identity with mithril.foo; uses the active Minecraft/resource-pack font. */
object Palette {
    val BACKGROUND = 0xFF101114.toInt()
    val SURFACE = 0xFF17181D.toInt()
    val SURFACE_RAISED = 0xFF1E2027.toInt()
    val BORDER = 0xFF2A2C34.toInt()
    val ACCENT = 0xFFB4B8FF.toInt()
    val HIGHLIGHT = 0xFFD2D5FF.toInt()
    val TEXT = 0xFFF0F0F3.toInt()
    val MUTED = 0xFFA0A2AE.toInt()
    val SUCCESS = 0xFF8DC7AC.toInt()
    val DANGER = 0xFFE5A4A4.toInt()
    val PRIMARY = 0xFFE8E9F3.toInt()
    val PRIMARY_TEXT = 0xFF1C1D28.toInt()
    val PRIMARY_HOVER = 0xFFFFFFFF.toInt()
    val SECONDARY_HOVER = 0xFF262833.toInt()

    fun button(primary: Boolean, active: Boolean, highlighted: Boolean): ButtonColors {
        val background =
            when {
                !active -> SURFACE_RAISED
                primary -> if (highlighted) PRIMARY_HOVER else PRIMARY
                highlighted -> SECONDARY_HOVER
                else -> SURFACE_RAISED
            }
        return ButtonColors(
            background,
            when {
                !active -> MUTED
                primary -> PRIMARY_TEXT
                else -> TEXT
            },
            when {
                active && highlighted -> ACCENT
                active && primary -> PRIMARY
                else -> BORDER
            },
        )
    }
}

data class ButtonColors(val background: Int, val text: Int, val border: Int)
