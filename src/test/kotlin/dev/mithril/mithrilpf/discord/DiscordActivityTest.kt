package dev.mithril.mithrilpf.discord

import dev.mithril.mithrilpf.dungeontimer.DungeonTimerState
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import dev.mithril.mithrilpf.party.FinderActivity
import dev.mithril.mithrilpf.party.PartyProtocol
import kotlin.test.*

class DiscordActivityTest {
    private val model = DiscordActivityModel()
    private val search = FinderActivity("M7", null, 0)

    private fun timer() =
        DungeonTimerState("M7", emptyList()).apply {
            chat(DungeonTimerState.START, TimerStamp(0, 0))
        }

    @Test
    fun `dungeon wins and elapsed clock does not restart on a split or wall clock change`() {
        val timer = timer()
        val first =
            model.activity(
                true,
                true,
                "M7",
                timer,
                TimerStamp(100, 5_000_000_000),
                105000,
                search,
            )!!
        assertEquals("M7 · Blood Rush", first.details)
        assertEquals(100L, first.start)
        timer.chat("[BOSS] The Watcher: Hello", TimerStamp(120, 6_000_000_000))
        val next =
            model.activity(
                true,
                true,
                "M7",
                timer,
                TimerStamp(180, 9_000_000_000),
                190000,
                search,
            )!!
        assertEquals("M7 · Blood Camp", next.details)
        assertEquals(first.start, next.start)
    }

    @Test
    fun `new runs reset elapsed and completed runs stop the clock`() {
        model.activity(true, true, "M7", timer(), TimerStamp(20, 1_000_000_000), 101000, null)
        val next = timer()
        assertEquals(
            200L,
            model.activity(true, true, "M7", next, TimerStamp(0, 0), 200000, null)!!.start,
        )
        next.chat("☠ Defeated Necron in 1m 5s", TimerStamp(1300, 65_000_000_000))
        val done =
            model.activity(true, true, "M7", next, TimerStamp(1400, 70_000_000_000), 270000, null)!!
        assertNull(done.start)
        assertEquals("Finished in 1m 5s", done.state)
    }

    @Test
    fun `search party waiting idle and disabled states`() {
        fun view(
            enabled: Boolean = true,
            dungeon: Boolean = false,
            party: FinderActivity? = search,
        ) = model.activity(enabled, dungeon, "M7", null, TimerStamp(0, 0), 1000, party)
        assertEquals("M7 · Looking for a party", view()!!.details)
        val party = view(party = FinderActivity("F7", "Example", 3))!!
        assertEquals("F7 · Party 3/5", party.details)
        assertEquals("Example's party", party.state)
        assertNull(party.start)
        assertNull(view(enabled = false, dungeon = true))
        assertNull(view(party = null))
        assertEquals("M7 · Preparing", view(dungeon = true)!!.details)
        assertEquals(
            "https://mithril.foo/party-finder",
            party.json()["buttons"].asJsonArray[0].asJsonObject["url"].asString,
        )
        assertFalse(party.json().has("secrets"))
    }

    @Test
    fun `activity summary is optional for old servers and validated when present`() {
        fun reply(activity: String?) =
            PartyProtocol.reply(
                    PartyProtocol.parse(
                        """{"version":1,"party":null${if (activity == null) "" else ",\"activity\":$activity"}}"""
                    )
                )
                .activity
        assertNull(reply(null))
        assertNull(reply("null"))
        assertEquals(search, reply("""{"floor":"M7","leader":null,"members":0}"""))
        assertEquals(
            FinderActivity("F7", "Alpha", 5),
            reply("""{"floor":"F7","leader":"Alpha","members":5}"""),
        )
        for (invalid in
            listOf(
                """{"floor":"F6","leader":null,"members":0}""",
                """{"floor":"M7","leader":null,"members":1}""",
                """{"floor":"M7","leader":"Alpha","members":6}""",
                """{"floor":"M7","leader":"Alpha","members":0}""",
                """{"floor":"M7","leader":"Alpha","members":1.5}""",
                """{"floor":"M7","leader":"A\nB","members":1}""",
            )) assertFails { reply(invalid) }
    }
}
