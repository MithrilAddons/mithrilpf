package dev.mithril.mithrilpf.soloroom

import com.google.gson.JsonParser
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import java.util.Base64
import kotlin.test.*

class RunTeleportsTest {
    private fun stamp(ms: Long) = TimerStamp(ms / 50, ms * 1_000_000)

    private fun click(replay: RunReplay, kind: Int, ms: Long) {
        replay.use(kind, ms * 1_000_000)
        replay.teleport(ms * 1_000_000)
    }

    private fun flags(replay: RunReplay): List<Int> {
        val bytes =
            Base64.getDecoder().decode(assertNotNull(replay.freeze()).encode()["samples"].asString)
        return (9 until bytes.size step 12).map { bytes[it].toInt() and 255 }
    }

    @Test
    fun `new encoder matches shared teleport fixture without adding samples`() {
        val replay = RunReplay()
        replay.begin(stamp(0), -185.0, -185.0, 0f)
        replay.observe(stamp(200), -184.0, -185.0, 90f, 1)
        click(replay, 1, 350)
        replay.observe(stamp(400), -153.0, -185.0, 180f, 1)
        repeat(3) { click(replay, 2, 450L + it * 30) }
        replay.observe(stamp(600), -140.0, -185.0, 180f, 2)
        click(replay, 3, 750)
        replay.observe(stamp(800), -125.0, -185.0, 180f, 2)
        repeat(6) { click(replay, if (it % 2 == 0) 1 else 2, 820L + it * 20) }
        replay.observe(stamp(1000), -112.0, -185.0, 180f, 3)
        replay.observe(stamp(1200), -111.0, -185.0, 180f, 3)
        replay.observe(stamp(15000), -111.0, -185.0, 0f, 3, finish = true)
        val expected =
            JsonParser.parseString(
                javaClass.getResource("/contracts/run-replay-teleports-v1.json")!!.readText()
            )
        assertEquals(expected, assertNotNull(replay.freeze()).encode())
    }

    @Test
    fun `rotation setbacks unmapped origins and long gaps remain plain breaks`() {
        val replay = RunReplay()
        replay.begin(stamp(0), -185.0, -185.0, 0f)
        click(replay, 1, 100)
        replay.observe(stamp(200), -184.0, -185.0, 90f, 0)
        click(replay, 1, 300)
        replay.observe(stamp(400), Double.NaN, -185.0, 0f, 0)
        replay.observe(stamp(600), -150.0, -185.0, 0f, 0)
        click(replay, 1, 700)
        replay.observe(stamp(1800), -120.0, -185.0, 0f, 0)
        assertEquals(listOf(129, 1, 3, 1, 1), flags(replay))
    }

    @Test
    fun `clicks expire are consumed reset and never affect inactive capture`() {
        val replay = RunReplay()
        replay.use(1, 0)
        replay.teleport(0)
        assertFalse(replay.active)
        replay.begin(stamp(0), -185.0, -185.0, 0f)
        replay.use(1, 0)
        replay.teleport(500_000_000)
        replay.observe(stamp(600), -175.0, -185.0, 0f, 0)
        click(replay, 2, 650)
        replay.observe(stamp(800), -165.0, -185.0, 0f, 0)
        replay.teleport(850_000_000)
        replay.observe(stamp(1000), -155.0, -185.0, 0f, 0)
        replay.use(1, 2_000_000_000)
        replay.teleport(1_100_000_000)
        replay.observe(stamp(1200), -145.0, -185.0, 0f, 0)
        replay.use(1, 1_250_000_000)
        replay.use(0, 1_260_000_000)
        replay.teleport(1_270_000_000)
        replay.observe(stamp(1400), -135.0, -185.0, 0f, 0)
        assertEquals(listOf(129, 17, 9, 17, 17, 17), flags(replay))
        replay.begin(stamp(2000), -185.0, -185.0, 0f)
        replay.teleport(2_100_000_000)
        replay.observe(stamp(2200), -175.0, -185.0, 0f, 0)
        assertEquals(listOf(129, 17), flags(replay))
    }

    @Test
    fun `predicted jumps and same millisecond replacement preserve valid flags`() {
        val replay = RunReplay()
        replay.begin(stamp(0), -185.0, -185.0, 0f)
        replay.observe(stamp(0), -185.0, -185.0, 0f, 0, finish = true)
        replay.observe(stamp(200), -170.0, -185.0, 0f, 0)
        replay.observe(stamp(200), -169.5, -185.0, 0f, 0, finish = true)
        click(replay, 1, 300)
        replay.observe(stamp(400), -150.0, -185.0, 0f, 0)
        replay.observe(stamp(400), Double.NaN, -185.0, 0f, 0, finish = true)
        assertEquals(listOf(129, 17, 3), flags(replay))
    }
}
