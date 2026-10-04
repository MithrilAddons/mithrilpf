package dev.mithril.mithrilpf.soloclear

import com.google.gson.JsonParser
import kotlin.test.*

class DungeonScoreTest {
    @Test
    fun `run summary reads integer secret count separately from percentage and retains crypts`() {
        val score = DungeonScore()
        assertNull(score.secretsFound)
        assertNull(score.crypts)
        score.tab(listOf("Secrets Found: 75.0%", "Secrets Found: 42", "Crypts: 7"))
        assertEquals(42, score.secretsFound)
        assertEquals(7, score.crypts)
        assertEquals(75.0, score.evidence(false)["secrets"].asDouble)
        score.tab(listOf("Secrets Found: 99999", "Secrets Found: nonsense", "Crypts: 101"))
        assertEquals(42, score.secretsFound)
        assertEquals(7, score.crypts)
    }

    @Test
    fun `backend score and serialized evidence share the same vectors`() {
        val cases =
            JsonParser.parseString(
                    javaClass.getResource("/contracts/solo-score-v2.json")!!.readText()
                )
                .asJsonArray
        for (item in cases) {
            val case = item.asJsonObject
            val evidence = case.getAsJsonObject("evidence")
            val state = DungeonScore()
            state.start()
            state.sidebar(listOf("Cleared: ${evidence["cleared"].asInt}% (0)"))
            val lines =
                mutableListOf(
                    "Completed Rooms: ${evidence["completed"].asInt}",
                    "Crypts: ${evidence["crypts"].asInt}",
                    "Puzzles: (${evidence["puzzles"].asInt})",
                )
            if (!evidence["secrets"].isJsonNull)
                lines += "Secrets Found: ${evidence["secrets"].asDouble}%"
            repeat(evidence["solved"].asInt) { lines += "Puzzle$it: [✔]" }
            state.tab(lines)
            state.mimic = evidence["mimic"].asBoolean
            if (evidence["prince"].asBoolean) state.chat("A prince falls. +1 bonus score")
            if (evidence["bat"].asBoolean) state.chat("A bat has been slain. +1 bonus score")
            val inBoss = evidence["in_boss"].asBoolean
            assertEquals(evidence, state.evidence(inBoss))
            assertEquals(
                case["score"].takeUnless { it.isJsonNull }?.asInt,
                state.estimate(case["elapsed_ms"].asLong / 1000, inBoss, case["paul"].asBoolean),
                case["name"].asString,
            )
        }
    }

    private fun score(
        completed: Int = 18,
        cleared: Int = 90,
        secrets: String = "100",
        crypts: Int = 5,
        puzzleRows: List<String> = listOf("Blaze: [✔]", "Ice Fill: [✔]"),
    ) =
        DungeonScore().apply {
            start()
            sidebar(listOf("Cleared: $cleared% (0)"))
            tab(
                listOf(
                    "Completed Rooms: $completed",
                    "Secrets Found: $secrets%",
                    "Crypts: $crypts",
                    "Puzzles: (2)",
                ) + puzzleRows
            )
        }

    @Test
    fun `missing mandatory observations never invent a score`() {
        val state = DungeonScore()
        state.start()
        assertNull(state.estimate(100, false, false))
        state.tab(listOf("Completed Rooms: 18", "Secrets Found: 100%", "Crypts: 5", "Puzzles: (0)"))
        assertNull(state.estimate(100, false, false))
        state.sidebar(listOf("Cleared: 0% (0)"))
        assertNull(state.estimate(100, false, false))
        state.sidebar(listOf("Cleared: 90% (0)"))
        assertEquals(305, state.estimate(100, false, false))
    }

    @Test
    fun `only an observed fresh start or explicit tab count establishes deaths`() {
        val state = DungeonScore()
        state.sidebar(listOf("Cleared: 90% (0)"))
        state.tab(listOf("Completed Rooms: 18", "Secrets Found: 100%", "Crypts: 5", "Puzzles: (0)"))
        assertNull(state.estimate(100, false, false))
        state.start()
        assertEquals(305, state.estimate(100, false, false))
        state.tab(listOf("Deaths: (1)"))
        assertEquals(304, state.estimate(100, false, false))
        state.tab(listOf("Deaths: 2"))
        assertEquals(302, state.estimate(100, false, false))
        state.tab(listOf("Deaths: 0"))
        assertEquals(2, state.deaths)
    }

    @Test
    fun `score includes secrets puzzles crypt cap bonuses and explicit paul toggle`() {
        assertEquals(305, score().estimate(600, false, false))
        assertEquals(285, score(secrets = "50").estimate(600, false, false))
        assertEquals(
            295,
            score(puzzleRows = listOf("Blaze: [✔]", "Ice Fill: [✦]")).estimate(600, false, false),
        )
        assertEquals(301, score(crypts = 1).estimate(600, false, false))
        assertEquals(305, score(crypts = 12).estimate(600, false, false))
        val state = score()
        state.chat("A PRINCE FALLS. +1 BONUS SCORE")
        state.chat("A Bat has been slain. +1 Bonus Score")
        state.chat("CHARM You charmed a Mimic and captured its shard.")
        assertEquals(309, state.estimate(600, false, false))
        assertEquals(319, state.estimate(600, false, true))
        state.chat("Party > FakeUser: 300 score! Mimic dead!")
        assertEquals(309, state.estimate(600, false, false))
    }

    @Test
    fun `invalid numbers and inconsistent room data do not become a 300`() {
        for (value in listOf("NaN", "Infinity", "101", "-1", "1.2.3")) {
            assertNull(score(secrets = value).estimate(0, false, false))
        }
        assertNull(score(completed = 0).estimate(0, false, false))
        assertNull(
            score(puzzleRows = listOf("A: [✔]", "B: [✔]", "C: [✔]")).estimate(0, false, false)
        )
    }

    @Test
    fun `watcher completion is not counted twice and speed falls after the limit`() {
        val state = score()
        assertEquals(305, state.estimate(840, false, false))
        state.chat("[BOSS] The Watcher: You have proven yourself. You may pass.")
        state.sidebar(listOf("Cleared: 95% (0)"))
        state.tab(
            listOf(
                "Completed Rooms: 19",
                "Secrets Found: 100%",
                "Crypts: 5",
                "Puzzles: (2)",
                "Blaze: [✔]",
                "Ice Fill: [✔]",
            )
        )
        assertEquals(305, state.estimate(840, false, false))
        assertEquals(298, state.estimate(840, true, false))
        assertTrue(state.estimate(1008, false, false)!! < 305)
    }
}
