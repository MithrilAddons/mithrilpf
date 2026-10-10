package dev.mithril.mithrilpf.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TextFitTest {
    private val measure = { text: String -> text.length * 6 }

    @Test
    fun `text that fits is left alone and longer text ends in an ellipsis`() {
        assertEquals("Fills soonest", ellipsize("Fills soonest", 200, measure))
        assertEquals("Lowest Cata…", ellipsize("Lowest Cata requirement", 72, measure))
        // A cut that would end on a space drops it instead of leaving "Lowest …".
        assertEquals("Lowest…", ellipsize("Lowest Cata", 48, measure))
        assertEquals("", ellipsize("Lowest", 5, measure))
    }

    @Test
    fun `the finder HUD sits below however many boss bars vanilla draws`() {
        assertEquals(6, finderHudTop(0, 240))
        assertEquals(25, finderHudTop(1, 240))
        assertEquals(63, finderHudTop(3, 240))
        // Vanilla stops at a third of the screen height, so extra bars don't push it further.
        assertEquals(82, finderHudTop(4, 240))
        assertEquals(82, finderHudTop(9, 240))
    }
}
