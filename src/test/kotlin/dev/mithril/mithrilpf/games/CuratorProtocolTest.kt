package dev.mithril.mithrilpf.games

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.*

class CuratorProtocolTest {
    private fun contract(part: String): String =
        JsonParser.parseString(javaClass.getResource("/contracts/curator-v1.json")!!.readText())
            .asJsonObject
            .get(part)
            .toString()

    @Test
    fun `a round in progress carries each guess with its feedback`() {
        val day = CuratorProtocol.day(contract("playing"))
        assertEquals(RoundState.PLAYING, day.state)
        assertEquals("2027-01-15", day.day)
        assertEquals(1, day.number)
        assertEquals(10, day.limit)
        assertEquals(1800057600, day.resetsAt)
        assertFalse(day.finished)
        val guess = day.guesses.single()
        assertEquals("Synthesizer v2", guess.name)
        assertTrue(guess.family)
        assertEquals(3_100_000.0, guess.values.market)
        assertEquals(mapOf("SKILL:COMBAT" to 22), guess.values.requirements)
        assertEquals(Feedback(Match.NONE, Arrow.UP), guess.feedback[Column.STAGE])
        assertEquals(Feedback(Match.EXACT), guess.feedback[Column.RARITY])
        assertNull(day.answer)
    }

    @Test
    fun `a finished round reveals the answer and the player's stats`() {
        val day = CuratorProtocol.day(contract("solved"))
        assertTrue(day.finished)
        assertEquals("Synthesizer v3", day.answer!!.name)
        assertEquals(ItemIcon("PAPER", null, null), day.answer!!.icon)
        assertEquals(PlayerStats(1, 1, 1, 1), day.stats)
    }

    @Test
    fun `the leaderboard keeps the season, standings and stats`() {
        val board = CuratorProtocol.leaderboard(contract("leaderboard"))
        assertEquals("2027-01", board.season)
        assertEquals(31, board.days)
        assertEquals(StandingRow(1, "Alice", 9, 1, 1, 1, true), board.top.single())
        assertNull(board.you)
        assertEquals(2.0, board.stats.average)
        assertEquals(1, board.stats.histogram[1])
    }

    @Test
    fun `a day still being prepared has no round yet`() {
        val day =
            CuratorProtocol.day(
                """{"version":1,"day":"2027-01-16","state":"preparing","resets_at":5}"""
            )
        assertEquals(RoundState.PREPARING, day.state)
        assertNull(day.number)
        assertTrue(day.guesses.isEmpty())
    }

    @Test
    fun `malformed responses are rejected`() {
        for (json in
            listOf(
                """{"version":2,"day":"2027-01-16","state":"preparing","resets_at":5}""",
                """{"version":1,"day":"16 Jan","state":"preparing","resets_at":5}""",
                """{"version":1,"day":"2027-01-16","state":"won","resets_at":5}""",
                contract("playing").replace("\"limit\":10", "\"limit\":0"),
            )) assertFails { CuratorProtocol.day(json) }
        assertFails {
            CuratorProtocol.day(contract("playing").replace("SYNTHESIZER_V2", "bad id!"))
        }
    }

    @Test
    fun `an unchanged catalog keeps the cached list`() {
        val catalog =
            CuratorProtocol.catalog(
                """{"version":1,"catalog":"3:1","items":[["HYPERION","Hyperion"]]}""",
                null,
            )
        assertEquals(listOf(CatalogItem("HYPERION", "Hyperion")), catalog.items)
        val unchanged = """{"version":1,"catalog":"3:1","unchanged":true}"""
        assertSame(catalog, CuratorProtocol.catalog(unchanged, catalog))
        assertFails { CuratorProtocol.catalog(unchanged, null) }
        assertFails { CuratorProtocol.catalog(unchanged, catalog.copy(version = "3:0")) }
        assertFails {
            CuratorProtocol.catalog("""{"version":1,"catalog":"3:1","items":[["X"]]}""", null)
        }
    }

    @Test
    fun `guess bodies name the day and the item`() {
        val body: JsonObject = CuratorProtocol.guessBody("2027-01-15", "HYPERION")
        assertEquals("""{"version":1,"day":"2027-01-15","item":"HYPERION"}""", body.toString())
    }
}
