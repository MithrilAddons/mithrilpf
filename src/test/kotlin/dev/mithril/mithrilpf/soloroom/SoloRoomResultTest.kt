package dev.mithril.mithrilpf.soloroom

import java.util.Locale
import kotlin.test.*
import kotlinx.serialization.json.*

class SoloRoomResultTest {
    @Test
    fun `first and faster results are green PBs but ties and slower results are not`() {
        assertEquals(SoloRoomResult("15.4s", true, 0x55FF55), SoloRoomResult.of(15400, null))
        assertTrue(SoloRoomResult.of(15400, 18200).newPb)
        assertFalse(SoloRoomResult.of(15400, 15400).newPb)
        assertFalse(SoloRoomResult.of(18200, 15400).newPb)
    }

    @Test
    fun `percentage thresholds are inclusive and use unrounded milliseconds`() {
        for (value in listOf(9999L, 10000L, 10999L, 11000L)) {
            assertEquals(0x55FF55, SoloRoomResult.of(value, 10000).color)
        }
        for (value in listOf(11001L, 12499L, 12500L)) {
            assertEquals(0xFFFF55, SoloRoomResult.of(value, 10000).color)
        }
        assertEquals(0xFF5555, SoloRoomResult.of(12501, 10000).color)
        assertEquals("11.0s", SoloRoomResult.of(11001, 10000).timeText)
        // Non-divisible PBs do not extend a boundary by rounding the threshold up.
        assertEquals(0x55FF55, SoloRoomResult.of(11001, 10001).color)
        assertEquals(0xFFFF55, SoloRoomResult.of(11002, 10001).color)
        assertEquals(0xFFFF55, SoloRoomResult.of(12501, 10001).color)
        assertEquals(0xFF5555, SoloRoomResult.of(12502, 10001).color)
    }

    @Test
    fun `format uses one decimal place and dot regardless of system locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("12.2s", SoloRoomResult.of(12200, null).timeText)
            assertEquals("18.3s", SoloRoomResult.of(18260, null).timeText)
            assertEquals("62.2s", SoloRoomResult.of(62200, null).timeText)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `translation has no unconditional PB marker or old verbose labels`() {
        val strings =
            javaClass
                .getResourceAsStream("/assets/mithrilpf/lang/en_us.json")!!
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
        fun text(key: String): String {
            val translated =
                when (key) {
                    "result" -> "room_result"
                    "pb" -> "pb_suffix"
                    "cleared" -> "clear"
                    else -> key
                }
            return strings.getValue("tracking.mithrilpf.$translated").jsonPrimitive.content
        }
        fun message(split: String, millis: Long, best: Long?): String {
            val result = SoloRoomResult.of(millis, best)
            return String.format(
                Locale.ROOT,
                text("result"),
                "Market",
                text(split),
                result.timeText,
            ) + if (result.newPb) text("pb") else ""
        }
        assertEquals("Market Clear: 12.2s", message("cleared", 12200, 12000))
        assertEquals("Market Secrets: 18.2s", message("secrets", 18200, 18000))
        assertEquals("Market Secrets: 15.4s (PB)", message("secrets", 15400, 18000))
        assertEquals("Market Secrets: 15.4s", message("secrets", 15400, 15400))
    }
}
