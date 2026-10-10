package dev.mithril.mithrilpf.dungeontimer

import kotlin.test.Test
import kotlin.test.assertEquals

class HudFitTest {
    @Test
    fun `a HUD that fits keeps its scale and a taller one shrinks to the screen`() {
        assertEquals(1.4, fittedScale(1.4, 120, 64, 320, 240))
        // A live M7 table is about 184 tall, so 1.4 would run 258 units down a 240 screen.
        assertEquals(240.0 / 184, fittedScale(1.4, 120, 184, 320, 240))
        assertEquals(320.0 / 210, fittedScale(1.6, 210, 100, 320, 240))
        assertEquals(240.0, fittedScale(500.0, 0, 1, 320, 240))
    }
}
