package dev.mithril.mithrilpf.dungeontimer

import java.util.Locale
import kotlin.test.*
import kotlinx.serialization.json.*

class SplitPbResultTest {
    @Test
    fun `tracking labels describe storage without judging legitimacy`() {
        val strings =
            javaClass
                .getResourceAsStream("/assets/mithrilpf/lang/en_us.json")!!
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
        assertEquals(
            "Local records · Not uploaded",
            strings.getValue("tracking.mithrilpf.local_only").jsonPrimitive.content,
        )
        assertEquals(
            "MithrilPF — personal bests",
            strings.getValue("tracking.mithrilpf.records").jsonPrimitive.content,
        )
    }

    @Test
    fun `300 score uses the same compact format and no legacy duplicate message`() {
        val strings =
            javaClass
                .getResourceAsStream("/assets/mithrilpf/lang/en_us.json")!!
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
        val result = assertNotNull(SplitPbResult.of(SplitTime(325740, 6500), null))
        val message =
            String.format(
                Locale.ROOT,
                strings.getValue("tracking.mithrilpf.split_pb").jsonPrimitive.content,
                "300 Score",
                result.timeText,
            ) + strings.getValue("tracking.mithrilpf.pb_suffix").jsonPrimitive.content
        assertEquals("300 Score: 325.7s (PB)", message)
        assertFalse(result.tickOnly)
        assertFalse("tracking.mithrilpf.pb" in strings)
        assertFalse("tracking.mithrilpf.solo_result" in strings)
    }

    @Test
    fun `first and real improvements show the current real time`() {
        val time = SplitTime(20400, 396)
        assertEquals(SplitPbResult("20.4s", false), SplitPbResult.of(time, null))
        assertEquals(SplitPbResult("20.4s", false), SplitPbResult.of(time, DungeonBest(21000, 400)))
        assertEquals(SplitPbResult("20.4s", false), SplitPbResult.of(time, DungeonBest(21000, 390)))
    }

    @Test
    fun `tick only improvement shows tick seconds rather than the slower real time`() {
        assertEquals(
            SplitPbResult("19.8s", true),
            SplitPbResult.of(SplitTime(22000, 396), DungeonBest(21000, 400)),
        )
        assertEquals(
            SplitPbResult("19.8s", true),
            SplitPbResult.of(SplitTime(21000, 396), DungeonBest(21000, 400)),
        )
        assertNull(SplitPbResult.of(SplitTime(21000, 400), DungeonBest(21000, 400)))
        assertNull(SplitPbResult.of(SplitTime(22000, 420), DungeonBest(21000, 400)))
    }

    @Test
    fun `compact chat matches room style with a truthful clock suffix`() {
        val strings =
            javaClass
                .getResourceAsStream("/assets/mithrilpf/lang/en_us.json")!!
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
        fun text(key: String) = strings.getValue("tracking.mithrilpf.$key").jsonPrimitive.content
        fun message(result: SplitPbResult) =
            String.format(Locale.ROOT, text("split_pb"), "Blood Rush", result.timeText) +
                text(if (result.tickOnly) "tick_pb_suffix" else "pb_suffix")
        assertEquals("Blood Rush: 20.4s (PB)", message(SplitPbResult("20.4s", false)))
        assertEquals("Blood Rush: 19.8s (tick PB)", message(SplitPbResult("19.8s", true)))
    }

    @Test
    fun `display uses one decimal and is independent of system locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("62.2s", SplitPbResult.of(SplitTime(62200, 1200), null)?.timeText)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
