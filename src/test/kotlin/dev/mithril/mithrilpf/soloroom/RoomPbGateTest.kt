package dev.mithril.mithrilpf.soloroom

import dev.mithril.mithrilpf.dungeontimer.DungeonBest
import dev.mithril.mithrilpf.dungeontimer.DungeonTimeFormat
import dev.mithril.mithrilpf.dungeontimer.SplitTime
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import java.util.Optional
import kotlin.test.*
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import org.junit.AfterClass
import org.junit.BeforeClass

class RoomPbGateTest {
    private fun span(ms: Long) = SplitTime(ms, ms / 50)

    private val regularClear = RoomResult("Waterfall", RoomStyle.REGULAR, clear = span(38_900))
    private val regularSecrets =
        RoomResult("Waterfall", RoomStyle.REGULAR, secrets = RoomSecrets(5, 8, 5, span(33_000)))
    private val midclear =
        RoomResult(
            "Waterfall",
            RoomStyle.MIDCLEAR,
            span(41_200),
            RoomSecrets(8, 8, 8, span(40_500)),
            span(41_200),
        )

    /** Each styled piece of a line, so colours can be checked. */
    private fun pieces(line: Component): List<Pair<String, Int?>> {
        val out = mutableListOf<Pair<String, Int?>>()
        line.visit(
            FormattedText.StyledContentConsumer<Unit> { style, text ->
                if (text.isNotEmpty()) out += text to style.color?.value
                Optional.empty()
            },
            Style.EMPTY,
        )
        return out
    }

    @Test
    fun `a clear saves straight away and reads like today's room line`() {
        val gate = RoomPbGate(Host())
        val outcome = gate.accept(regularClear, emptyMap())
        assertEquals(mapOf("Waterfall · Clear · Regular" to span(38_900)), outcome.save)
        assertEquals(listOf("Waterfall Clear: 38.9s (PB)"), outcome.lines.map { it.string })
        val colours = pieces(outcome.lines.single()).toMap()
        assertEquals(0x55FF55, colours["38.9s"])
        assertEquals(0xFFAA00, colours[" (PB)"])
    }

    @Test
    fun `secrets wait for the gate and save once it passes`() {
        val gate = RoomPbGate(Host())
        val outcome = gate.accept(regularSecrets, emptyMap())
        assertTrue(outcome.save.isEmpty())
        assertEquals(
            listOf("Waterfall Secrets: 5/8 9.1/min (PB) · needs 300"),
            outcome.lines.map { it.string },
        )
        val (save, dropped) = gate.decide(true, null)
        assertEquals(mapOf("Waterfall · Secrets · Regular" to SplitTime(6600, 132)), save)
        assertNull(dropped)
        assertEquals(true, gate.passed)
        val later = gate.accept(regularSecrets, emptyMap())
        assertEquals(save, later.save)
        assertEquals(listOf("Waterfall Secrets: 5/8 9.1/min (PB)"), later.lines.map { it.string })
        assertEquals(emptyMap<String, SplitTime>() to null, gate.decide(false, 10))
    }

    @Test
    fun `a missed gate drops what was waiting and says so only if it held a PB`() {
        val gate = RoomPbGate(Host())
        gate.accept(regularSecrets, emptyMap())
        val (save, dropped) = gate.decide(false, 287)
        assertTrue(save.isEmpty())
        assertEquals("Room PBs not saved: 287 at boss entry", dropped?.string)
        val slower = RoomPbGate(Host())
        val best = mapOf("Waterfall · Secrets · Regular" to DungeonBest(1000, 20))
        assertEquals(
            listOf("Waterfall Secrets: 5/8 9.1/min · needs 300"),
            slower.accept(regularSecrets, best).lines.map { it.string },
        )
        assertEquals(emptyMap<String, SplitTime>() to null, slower.decide(false, null))
        val unknown = RoomPbGate(Host())
        unknown.accept(regularSecrets, emptyMap())
        assertEquals(
            "Room PBs not saved: ? at boss entry",
            unknown.decide(false, null).second?.string,
        )
    }

    @Test
    fun `a midclear is one line with time secrets and total`() {
        val gate = RoomPbGate(Host())
        val best =
            mapOf(
                "Waterfall · Clear · Midclear" to DungeonBest(36_000, 700),
                "Waterfall · Total" to DungeonBest(60_000, 1200),
            )
        val outcome = gate.accept(midclear, best)
        assertEquals(mapOf("Waterfall · Clear · Midclear" to span(41_200)), outcome.save)
        assertEquals(
            listOf(
                "Waterfall Midclear: Time 41.2s · Secrets 8/8 11.9/min (PB) · Total 41.2s (PB) · needs 300"
            ),
            outcome.lines.map { it.string },
        )
        assertEquals(0xFFFF55, pieces(outcome.lines.single()).first { it.first == "41.2s" }.second)
        assertEquals(
            setOf("Waterfall · Secrets · Midclear", "Waterfall · Total"),
            gate.decide(true, null).first.keys,
        )
    }

    @Test
    fun `parts that did not count are left out of the line`() {
        val gate = RoomPbGate(Host())
        val totalOnly = RoomResult("Room", RoomStyle.REGULAR, total = span(10_000))
        assertEquals(
            listOf("Room Secrets: Total 10.0s (PB) · needs 300"),
            gate.accept(totalOnly, emptyMap()).lines.map { it.string },
        )
        val timeOnly = RoomResult("Room", RoomStyle.MIDCLEAR, clear = span(5_000))
        val outcome = gate.accept(timeOnly, emptyMap())
        assertEquals(listOf("Room Midclear: Time 5.0s (PB)"), outcome.lines.map { it.string })
        val plain = RoomResult("Plain", null, clear = span(5_000))
        assertEquals(mapOf("Plain · Clear" to span(5_000)), gate.accept(plain, emptyMap()).save)
    }

    @Test
    fun `a midclear's own clear PB and a secrets-only midclear`() {
        val gate = RoomPbGate(Host())
        assertEquals(
            listOf(
                "Waterfall Midclear: Time 41.2s (PB) · Secrets 8/8 11.9/min (PB) · Total 41.2s (PB) · needs 300"
            ),
            gate.accept(midclear, emptyMap()).lines.map { it.string },
        )
        val secretsOnly = midclear.copy(clear = null, total = null)
        assertEquals(
            listOf("Waterfall Midclear: Secrets 8/8 11.9/min (PB) · needs 300"),
            gate.accept(secretsOnly, emptyMap()).lines.map { it.string },
        )
        val host = Host()
        RoomPbGate(host).bossEntry(null) { 300 }
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun `a room reported again replaces what it was waiting with`() {
        val host = Host()
        val gate = RoomPbGate(host)
        gate.accept(regularSecrets, emptyMap())
        val more =
            RoomResult("Waterfall", RoomStyle.REGULAR, secrets = RoomSecrets(6, 8, 6, span(60_000)))
        gate.accept(more, emptyMap())
        assertEquals(
            mapOf("Waterfall · Secrets · Regular" to SplitTime(10_000, 200)),
            gate.decide(true, null).first,
        )
        val withdrawn = RoomPbGate(host)
        withdrawn.accept(regularSecrets, emptyMap())
        assertTrue(
            withdrawn.accept(RoomResult("Waterfall", RoomStyle.REGULAR), emptyMap()).lines.isEmpty()
        )
        assertTrue(
            withdrawn
                .accept(RoomResult("Waterfall", RoomStyle.MIDCLEAR), emptyMap())
                .lines
                .isEmpty()
        )
        assertEquals(emptyMap<String, SplitTime>() to null, withdrawn.decide(false, 250))
    }

    @Test
    fun `clearing drops waiting results without deciding the gate`() {
        val gate = RoomPbGate(Host())
        gate.accept(regularSecrets, emptyMap())
        gate.clear()
        assertNull(gate.passed)
        assertEquals(emptyMap<String, SplitTime>() to null, gate.decide(false, 200))
    }

    @Test
    fun `results are saved and shown through the host`() {
        val host = Host()
        RoomPbGate(host).results(listOf(regularClear, regularSecrets))
        assertEquals(
            listOf(mapOf("Waterfall · Clear · Regular" to span(38_900)), emptyMap()),
            host.saves,
        )
        assertEquals(
            listOf(
                "Waterfall Clear: 38.9s (PB)",
                "Waterfall Secrets: 5/8 9.1/min (PB) · needs 300",
            ),
            host.sent,
        )
    }

    @Test
    fun `boss entry finishes white rooms then passes or drops on the estimate`() {
        fun run(estimate: Int?): Host {
            val host = Host()
            val gate = RoomPbGate(host)
            val rooms = SoloRoomState("Alice").apply { start() }
            val now = { tick: Long -> TimerStamp(tick, tick * 50_000_000) }
            rooms.enter("Room", true, false, false, now(10))
            rooms.secrets("Room", 0, 2, now(10))
            gate.results(rooms.observe("Room", false, now(20)))
            rooms.secrets("Room", 1, 2, now(30))
            gate.bossEntry(rooms) { estimate }
            gate.bossEntry(rooms) { fail("decided once") }
            gate.soloPassed(rooms)
            assertFalse(rooms.eligible)
            return host
        }
        val passed = run(300)
        assertEquals(mapOf("Room · Secrets · Regular" to SplitTime(500, 10)), passed.saves.last())
        assertEquals("Saved 1 room PBs", passed.sent.last())
        assertEquals("Room PBs not saved: 299 at boss entry", run(299).sent.last())
        assertEquals("Room PBs not saved: ? at boss entry", run(null).sent.last())
    }

    @Test
    fun `a passed gate with nothing improved says nothing`() {
        val host = Host(improved = 0)
        val gate = RoomPbGate(host)
        gate.accept(regularSecrets, emptyMap())
        gate.soloPassed(null)
        assertTrue(host.sent.isEmpty())
        assertEquals(1, host.saves.single().size)
    }

    private class Host(val improved: Int? = null) : RoomPbGate.Host {
        val saves = mutableListOf<Map<String, SplitTime>>()
        val sent = mutableListOf<String>()

        override fun best() = emptyMap<String, DungeonBest>()

        override fun save(times: Map<String, SplitTime>, saved: (Int) -> Unit) {
            saves += times
            saved(improved ?: times.size)
        }

        override fun send(line: Component) {
            sent += line.string
        }
    }

    @Test
    fun `the PB list shows rates and labels old clears until both styles have a time`() {
        val best = DungeonBest(6600, 132)
        val time = DungeonTimeFormat.pair(SplitTime(6600, 132))
        assertEquals(
            "A · Secrets · Regular: 9.1/min",
            RoomPbGate.listing("A · Secrets · Regular", best, emptyMap()),
        )
        assertEquals("A · Total: $time", RoomPbGate.listing("A · Total", best, emptyMap()))
        assertEquals(
            "A · Clear (style unknown): $time",
            RoomPbGate.listing("A · Cleared", best, mapOf("A · Clear · Regular" to best)),
        )
        assertNull(
            RoomPbGate.listing(
                "A · Cleared",
                best,
                mapOf("A · Clear · Regular" to best, "A · Clear · Midclear" to best),
            )
        )
    }

    companion object {
        private var previous: Language? = null

        @JvmStatic
        @BeforeClass
        fun language() {
            previous = Language.getInstance()
            val strings = mutableMapOf<String, String>()
            Language.loadFromJson(
                RoomPbGateTest::class
                    .java
                    .getResourceAsStream("/assets/mithrilpf/lang/en_us.json")!!,
                strings::put,
            )
            Language.inject(
                object : Language() {
                    override fun getOrDefault(key: String, fallback: String) =
                        strings[key] ?: fallback

                    override fun has(key: String) = key in strings

                    override fun isDefaultRightToLeft() = false

                    override fun getVisualOrder(text: FormattedText) = FormattedCharSequence.EMPTY
                }
            )
        }

        @JvmStatic
        @AfterClass
        fun restore() {
            previous?.let(Language::inject)
        }
    }
}
