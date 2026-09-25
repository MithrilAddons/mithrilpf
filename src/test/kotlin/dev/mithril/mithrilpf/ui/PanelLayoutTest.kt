package dev.mithril.mithrilpf.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanelLayoutTest {
    @Test
    fun normalPanelIsCenteredAndBounded() {
        assertEquals(PanelLayout(320, 164, 320, 212), PanelLayout.fit(960, 540))
    }

    @Test
    fun layoutFitsMinecraftGuiSizes() {
        for ((width, height) in listOf(320 to 240, 427 to 240, 854 to 480, 1920 to 1080)) {
            val panel = PanelLayout.fit(width, height)
            assertTrue(panel.x >= 8 && panel.y >= 8)
            assertTrue(panel.x + panel.width <= width - 8)
            assertTrue(panel.y + panel.height <= height - 8)
        }
    }

    @Test
    fun websitePaletteIsPreserved() {
        assertEquals(0xFF101114.toInt(), Palette.BACKGROUND)
        assertEquals(0xFF17181D.toInt(), Palette.SURFACE)
        assertEquals(0xFF1E2027.toInt(), Palette.SURFACE_RAISED)
        assertEquals(0xFF2A2C34.toInt(), Palette.BORDER)
        assertEquals(0xFFB4B8FF.toInt(), Palette.ACCENT)
        assertEquals(0xFFD2D5FF.toInt(), Palette.HIGHLIGHT)
        assertEquals(0xFFF0F0F3.toInt(), Palette.TEXT)
        assertEquals(0xFFA0A2AE.toInt(), Palette.MUTED)
        assertEquals(0xFF8DC7AC.toInt(), Palette.SUCCESS)
        assertEquals(0xFFE5A4A4.toInt(), Palette.DANGER)
    }

    @Test
    fun primaryActionUsesLightSurfaceAndDarkText() {
        assertEquals(
            ButtonColors(Palette.PRIMARY, Palette.PRIMARY_TEXT, Palette.PRIMARY),
            Palette.button(primary = true, active = true, highlighted = false),
        )
        assertEquals(
            ButtonColors(Palette.PRIMARY_HOVER, Palette.PRIMARY_TEXT, Palette.ACCENT),
            Palette.button(primary = true, active = true, highlighted = true),
        )
    }

    @Test
    fun secondaryActionHasNeutralTextAndVisibleFocus() {
        assertEquals(
            ButtonColors(Palette.SURFACE_RAISED, Palette.TEXT, Palette.BORDER),
            Palette.button(primary = false, active = true, highlighted = false),
        )
        assertEquals(
            ButtonColors(Palette.SECONDARY_HOVER, Palette.TEXT, Palette.ACCENT),
            Palette.button(primary = false, active = true, highlighted = true),
        )
    }

    @Test
    fun disabledActionsDoNotLookHighlighted() {
        for (primary in listOf(true, false)) {
            for (highlighted in listOf(true, false)) {
                assertEquals(
                    ButtonColors(Palette.SURFACE_RAISED, Palette.MUTED, Palette.BORDER),
                    Palette.button(primary, active = false, highlighted),
                )
            }
        }
    }
}
