package dev.mithril.mithrilpf.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.mithril.mithrilpf.dungeontimer.DungeonTimerState
import dev.mithril.mithrilpf.dungeontimer.SplitTime
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import dev.mithril.mithrilpf.soloclear.DungeonScore
import dev.mithril.mithrilpf.soloclear.SoloClearState
import dev.mithril.mithrilpf.soloroom.CapturedMap
import dev.mithril.mithrilpf.soloroom.RunReplay
import java.util.UUID
import kotlin.test.*

class RecordCaptureTest {
    private val capture = RecordCapture()

    @Test
    fun `replay is detached from JSON and only queued with a completed map`() {
        val recorder = RunReplay()
        recorder.begin(stamp(10), -185.0, -185.0, 0f)
        recorder.observe(stamp(16), -185.0, -185.0, 0f, 1, finish = true)
        val replay = assertNotNull(recorder.freeze())
        begin()
        body()
        val map = JsonObject().apply { addProperty("version", 2) }
        capture.progress(stamp(15), false, "tracking", JsonObject(), map = map, replay = replay)
        assertNull(assertNotNull(capture.poll()).replay)
        capture.sample(
            stamp(16),
            false,
            null,
            DungeonScore(),
            null,
            complete = true,
            map = CapturedMap(map, replay),
        )
        val event = assertNotNull(capture.poll())
        assertSame(replay, event.replay)
        assertFalse(event.body.contains("samples"))
        begin()
        body()
        capture.progress(
            stamp(16),
            false,
            "completed",
            JsonObject(),
            complete = true,
            replay = replay,
        )
        assertNull(assertNotNull(capture.poll()).replay)
    }

    @Test
    fun `map is frozen only in the completion event`() {
        begin()
        body()
        val map = JsonObject().apply { addProperty("version", 1) }
        capture.progress(stamp(15), false, "tracking", JsonObject(), map = map)
        assertFalse(body().has("map"))
        capture.progress(stamp(16), false, "completed", JsonObject(), complete = true, map = map)
        map.addProperty("version", 2)
        assertEquals(1, body()["map"].asJsonObject["version"].asInt)
        progress(20)
        assertNull(capture.poll())
    }

    private fun stamp(seconds: Long) = TimerStamp(seconds * 20, seconds * 1_000_000_000)

    private fun body() = JsonParser.parseString(assertNotNull(capture.poll()).body).asJsonObject

    private fun begin(floor: String = "F7", eligible: Boolean = true) =
        capture.begin(floor, stamp(10), 123456, eligible, false)

    private fun progress(seconds: Long, status: String? = "tracking", complete: Boolean = false) =
        capture.progress(stamp(seconds), status == "death", status, JsonObject(), complete)

    @Test
    fun `tab capture filters nonparticipants and snapshots score only when a sample is due`() {
        val self = UUID.fromString("01234567-89ab-4def-8123-456789abcdef")
        val fake = UUID.fromString("11234567-89ab-2def-8123-456789abcdef")
        val tab = mapOf(fake to "[30] Player (Mage XX)", UUID.randomUUID() to "Secrets Found: 20%")
        capture.observeTab(tab.values, listOf("Player" to fake, "Player" to self))
        val state = SoloClearState("Player")
        state.begin("F7", stamp(10))
        val score = DungeonScore()
        capture.sample(stamp(10), false, state, score, null)
        assertNull(capture.poll())
        begin()
        body()
        capture.sample(stamp(14), false, state, score, null)
        assertNull(capture.poll())
        capture.sample(stamp(15), false, state, score, null)
        val sample = body()
        assertEquals(
            listOf(self.toString().replace("-", "")),
            sample["roster"].asJsonArray.map { it.asString },
        )
        assertFalse(sample["evidence"].asJsonObject["in_boss"].asBoolean)
        val timer = DungeonTimerState("F7", emptyList())
        capture.sample(stamp(20), false, state, score, timer)
        assertFalse(body()["evidence"].asJsonObject["in_boss"].asBoolean)
        timer.completed["Boss Entry"] = SplitTime(100, 2)
        capture.sample(stamp(21), false, null, score, timer, complete = true)
        val end = body()
        assertTrue(end["evidence"].asJsonObject["in_boss"].asBoolean)
        assertFalse(end["valid"].asBoolean)
    }

    @Test
    fun `unresolved teammate cannot disappear from a solo roster`() {
        val self = UUID.randomUUID()
        val teammate = UUID.randomUUID()
        val fake = UUID.fromString("11234567-89ab-2def-8123-456789abcdef")
        val lines = listOf("[30] Player (Mage XX)", "[30] Teammate (Tank XX)")
        capture.observeTab(lines, listOf("Player" to self, "Teammate" to fake))
        begin()
        body()
        progress(15)
        assertTrue(body()["roster"].asJsonArray.isEmpty)
        capture.observeTab(listOf(lines.first()), listOf("Player" to self))
        progress(20)
        assertTrue(body()["roster"].asJsonArray.isEmpty)
        capture.terminal("M7", SplitTime(40000, 800), stamp(60))
        assertNull(capture.poll())
        capture.observeTab(emptyList(), listOf("Teammate" to teammate))
        progress(25)
        assertEquals(
            setOf(self, teammate).map { it.toString().replace("-", "") }.toSet(),
            body()["roster"].asJsonArray.map { it.asString }.toSet(),
        )
    }

    @Test
    fun `terminal clients agree on real roster despite different display rows and profile order`() {
        val profiles = (1..5).map { "Player$it" to UUID.randomUUID() }
        val lines = profiles.map { (name, _) -> "[30] $name (Mage XX)" }
        val reports =
            listOf(profiles, profiles.reversed()).map { ordered ->
                val client = RecordCapture()
                client.observeTab(
                    lines.reversed() + "Secrets Found: 100%",
                    ordered + ("Unrelated" to UUID.randomUUID()),
                )
                client.begin("M7", stamp(10), 123456, false, false)
                client.observeTab(
                    listOf("[30] PLAYER1 (DEAD)"),
                    listOf("player1" to profiles.first().second),
                )
                client.terminal("M7", SplitTime(40000, 800), stamp(60))
                JsonParser.parseString(assertNotNull(client.poll()).body)
                    .asJsonObject["roster"]
                    .asJsonArray
                    .map { it.asString }
            }
        assertEquals(
            profiles.map { it.second.toString().replace("-", "") }.sorted(),
            reports.first(),
        )
        assertEquals(reports.first(), reports.last())
    }

    @Test
    fun `reset forgets previous participant identities and waits for fresh profiles`() {
        val self = UUID.randomUUID()
        val line = "[30] Player (Mage XX)"
        capture.observeTab(listOf(line, "[30] Teammate (Tank XX)"), listOf("Player" to self))
        capture.reset()
        capture.observeTab(listOf(line), emptyList())
        begin(eligible = false)
        capture.terminal("M7", SplitTime(40000, 800), stamp(60))
        assertNull(capture.poll())
        capture.observeTab(listOf(line), listOf("PLAYER" to self))
        capture.terminal("M7", SplitTime(40000, 800), stamp(60))
        assertEquals(
            listOf(self.toString().replace("-", "")),
            body()["roster"].asJsonArray.map { it.asString },
        )
    }

    @Test
    fun `capture samples every five seconds and preserves completion between samples`() {
        capture.observeRoster(listOf("b", "a", "a"))
        begin()
        val start = body()
        assertEquals("F7", start["floor"].asString)
        assertEquals(0, start["elapsed_ms"].asInt)
        assertFalse(start["paul"].asBoolean)
        progress(14)
        assertNull(capture.poll())
        progress(15)
        val sample = body()
        assertEquals(5000, sample["elapsed_ms"].asInt)
        assertEquals(100, sample["ticks"].asInt)
        assertEquals(listOf("a", "b"), sample["roster"].asJsonArray.map { it.asString })
        assertTrue(sample["valid"].asBoolean)
        assertFalse(sample["dead"].asBoolean)
        progress(16, "completed", true)
        assertTrue(body()["complete"].asBoolean)
        progress(25)
        assertNull(capture.poll())
    }

    @Test
    fun `death invalid status reset and explicit invalidation stop capture`() {
        for (status in listOf("death", "not_solo", null)) {
            begin()
            body()
            progress(15, status)
            val sample = body()
            assertFalse(sample["valid"].asBoolean)
            assertEquals(status == "death", sample["dead"].asBoolean)
            progress(20)
            assertNull(capture.poll())
        }
        begin()
        body()
        capture.invalidate()
        progress(15)
        assertNull(capture.poll())
        begin()
        body()
        capture.reset()
        progress(15)
        assertNull(capture.poll())
    }

    @Test
    fun `unsupported and ineligible runs stay local while late solo roster is accepted`() {
        begin("F6")
        progress(15)
        assertNull(capture.poll())
        begin("M7", false)
        progress(15)
        assertNull(capture.poll())
        capture.begin("M7", stamp(10), 123456, true, true)
        assertTrue(body()["paul"].asBoolean)
        progress(15, "awaiting_roster")
        assertTrue(body()["valid"].asBoolean)
        capture.observeRoster(listOf("self"))
        progress(20)
        assertEquals("self", body()["roster"].asJsonArray.single().asString)
    }

    @Test
    fun `bounded queue abandons a congested live attempt and next run gets a new identity`() {
        begin()
        val first = assertNotNull(capture.poll()).run
        repeat(9) { progress(15L + it * 5) }
        assertNull(capture.poll())
        progress(100)
        assertNull(capture.poll())
        begin()
        assertTrue(assertNotNull(capture.poll()).run > first)
        progress(15)
        capture.clearEvents()
        assertNull(capture.poll())
    }

    @Test
    fun `terminal reports require a supported started run and observed roster`() {
        val time = SplitTime(40000, 790)
        capture.terminal("F7", time, stamp(60))
        assertNull(capture.poll())
        begin(eligible = false)
        capture.terminal("F7", time, stamp(60))
        assertNull(capture.poll())
        capture.observeRoster(listOf("self"))
        capture.terminal("F6", time, stamp(60))
        assertNull(capture.poll())
        capture.terminal("F7", null, stamp(60))
        assertNull(capture.poll())
        capture.terminal("F7", time, stamp(60))
        val report = body()
        assertEquals(123456, report["run_started_ms"].asInt)
        assertEquals(40000, report["real_ms"].asInt)
        assertEquals(790, report["ticks"].asInt)
        assertEquals(2, report["version"].asInt)
        assertTrue(report["report_id"].asString.matches(Regex("[a-f0-9]{32}")))
        capture.reset()
        begin(eligible = false)
        capture.terminal("M7", time, stamp(60))
        assertNull(capture.poll())
    }
}
