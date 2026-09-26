package dev.mithril.mithrilpf.dungeontimer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NumericCounterLayoutTest {
    @Test
    fun `one and two digit countdowns share centered bounds`() {
        val one = NumericCounterLayout.measure(6, 12, 9)
        val two = NumericCounterLayout.measure(12, 12, 9)
        assertEquals(one, two)
        assertEquals(4 to 1, one.textOrigin(6, 9))
        assertEquals(1 to 1, two.textOrigin(12, 9))
    }

    @Test
    fun `split preview and short live values have matching bounds`() {
        val preview = NumericCounterLayout.measure(32, 36, 9)
        val live = NumericCounterLayout.measure(24, 36, 9)
        assertEquals(preview, live)
        assertEquals(6 to 1, live.textOrigin(24, 9))
    }

    @Test
    fun `long durations and taller fonts expand without clipping`() {
        val box = NumericCounterLayout.measure(80, 36, 16)
        assertEquals(80, box.width)
        assertTrue(box.height >= 16)
        assertEquals(0 to 1, box.textOrigin(80, 16))
    }
}
