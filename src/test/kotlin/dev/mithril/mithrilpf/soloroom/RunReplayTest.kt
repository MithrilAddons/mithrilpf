package dev.mithril.mithrilpf.soloroom

import com.google.gson.JsonParser
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.*

class RunReplayTest {
    private fun stamp(ms: Long) = TimerStamp(ms / 50, ms * 1_000_000)

    private fun bytes(snapshot: ReplaySnapshot) =
        Base64.getDecoder().decode(snapshot.encode()["samples"].asString)

    @Test
    fun `five hertz replay matches shared wire fixture and freezes at completion`() {
        val replay = RunReplay()
        replay.begin(stamp(0), -185.0, -185.0, 0f)
        replay.observe(stamp(50), -184.5, -185.0, 30f, 1)
        replay.observe(stamp(200), -184.0, -185.0, 90f, 1)
        replay.observe(stamp(400), -153.0, -185.0, 180f, 1)
        replay.observe(stamp(600), -153.0, -184.0, 270f, 3)
        replay.observe(stamp(800), Double.NaN, -184.0, 0f, 2)
        replay.observe(stamp(1000), -153.0, -183.0, -90f, null)
        replay.observe(stamp(15000), -153.0, -183.0, 360f, 3, finish = true)
        val frozen = assertNotNull(replay.freeze())
        val expected = javaClass.getResource("/contracts/run-replay-v1.json")!!.readText()
        assertEquals(JsonParser.parseString(expected), frozen.encode())
        replay.observe(stamp(15200), -150.0, -183.0, 0f, 4)
        assertNull(replay.freeze())
        replay.begin(stamp(20000), -185.0, -185.0, 0f)
        assertNull(replay.freeze())
        assertEquals(JsonParser.parseString(expected), frozen.encode())
    }

    @Test
    fun `early final sample and explicit short teleport retain the exact endpoint`() {
        val replay = RunReplay()
        assertNull(replay.freeze())
        replay.observe(stamp(0), 0.0, 0.0, 0f, null)
        replay.begin(stamp(100), -185.0, -185.0, 0f)
        replay.discontinuity()
        replay.observe(stamp(300), -184.0, -185.0, 0f, -1)
        replay.observe(stamp(300), -183.0, -185.0, 90f, 1, finish = true)
        val data =
            ByteBuffer.wrap(bytes(assertNotNull(replay.freeze()))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, data.limit())
        assertEquals(200, data.getInt(12))
        assertEquals(-2928, data.getShort(16).toInt())
        assertEquals(1, data.get(21).toInt())
        assertEquals(1, data.getShort(22).toInt())
        replay.begin(stamp(0), -185.0, -185.0, 0f)
        replay.observe(stamp(150), -184.0, -185.0, 0f, 3601, finish = true)
        val end =
            ByteBuffer.wrap(bytes(assertNotNull(replay.freeze()))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(150, end.getInt(12))
        assertEquals(0, end.getShort(22).toInt())
    }

    @Test
    fun `two hour bound stays fixed and broken clocks or expired runs discard replay`() {
        val replay = RunReplay()
        replay.begin(stamp(0), -185.0, -185.0, 0f)
        for (ms in 200L..7_200_000L step 200L) replay.observe(stamp(ms), -185.0, -185.0, 0f, 0)
        replay.observe(stamp(7_200_000), -185.0, -185.0, 0f, 0, finish = true)
        assertEquals(36001 * 12, bytes(assertNotNull(replay.freeze())).size)
        replay.begin(stamp(0), -185.0, -185.0, 0f)
        replay.observe(stamp(7_200_001), -185.0, -185.0, 0f, 0, finish = true)
        assertNull(replay.freeze())
        replay.begin(stamp(0), -185.0, -185.0, 0f)
        replay.observe(stamp(200), -185.0, -185.0, 0f, 0)
        replay.observe(stamp(100), -185.0, -185.0, 0f, 0)
        assertNull(replay.freeze())
    }
}
