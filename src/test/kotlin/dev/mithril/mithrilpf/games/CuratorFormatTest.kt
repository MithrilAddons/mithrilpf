package dev.mithril.mithrilpf.games

import kotlin.test.*

class CuratorFormatTest {
    private val synth =
        ClueValues(
            rarity = "EPIC",
            type = "NECKLACE",
            museum = "COMBAT",
            stage = "PROFESSIONAL",
            requirements = mapOf("SKILL:COMBAT" to 22),
            soulbound = null,
            origin = null,
            market = 5_400_000.0,
            npc = 17440.0,
            length = 14,
        )

    @Test
    fun `wide and narrow cells use the mockup abbreviations`() {
        val wide = Column.entries.map { CuratorFormat.cell(it, synth, narrow = false) }
        assertEquals(
            listOf("EPIC", "Neck.", "Combat", "Pro.", "Cmb 22", "No", "—", "5.4M", "17.4k", "14"),
            wide,
        )
        val narrow = Column.entries.map { CuratorFormat.cell(it, synth, narrow = true) }
        assertEquals(
            listOf("E", "Nck", "Cmb", "Pr", "C22", "No", "—", "5.4M", "17.4k", "14"),
            narrow,
        )
    }

    @Test
    fun `unknown and missing values still read sensibly`() {
        val elevator =
            synth.copy(
                rarity = "SPECIAL",
                type = null,
                museum = "SPECIAL",
                stage = null,
                requirements = emptyMap(),
                soulbound = "COOP",
                origin = "RIFT",
                market = null,
                npc = null,
            )
        val cells = Column.entries.map { CuratorFormat.cell(it, elevator, narrow = false) }
        assertEquals(
            listOf("SPEC", "—", "Special", "—", "—", "Co-op", "Rift", "—", "—", "14"),
            cells,
        )
        assertEquals("Not tradeable", CuratorFormat.full(Column.MARKET, elevator))
        assertEquals("Not in museum", CuratorFormat.full(Column.MUSEUM, synth.copy(museum = null)))
        assertEquals(
            "Gauntl",
            CuratorFormat.cell(Column.TYPE, synth.copy(type = "GAUNTLET"), false),
        )
        assertEquals("Mys", CuratorFormat.cell(Column.RARITY, synth.copy(rarity = "MYSTERY"), true))
    }

    @Test
    fun `requirements show the most recognisable one and hint at more`() {
        val sword =
            synth.copy(
                requirements =
                    mapOf("DUNGEON_SKILL:CATACOMBS" to 30, "SKILL:COMBAT" to 25, "ANY_OF" to null)
            )
        assertEquals("Cmb 25+", CuratorFormat.cell(Column.REQUIREMENTS, sword, false))
        assertEquals("C25+", CuratorFormat.cell(Column.REQUIREMENTS, sword, true))
        assertEquals(
            "Catacombs 30, Combat 25, Any Of",
            CuratorFormat.full(Column.REQUIREMENTS, sword),
        )
        val slayer = synth.copy(requirements = mapOf("SLAYER:WOLF" to 7))
        assertEquals("Sven 7", CuratorFormat.cell(Column.REQUIREMENTS, slayer, false))
        assertEquals("Wolf Slayer 7", CuratorFormat.full(Column.REQUIREMENTS, slayer))
        val choice = synth.copy(requirements = mapOf("ANY_OF" to null))
        assertEquals("Any", CuratorFormat.cell(Column.REQUIREMENTS, choice, false))
        assertEquals(
            "None",
            CuratorFormat.full(Column.REQUIREMENTS, synth.copy(requirements = emptyMap())),
        )
    }

    @Test
    fun `amounts round like the mockups`() {
        assertEquals("140k", CuratorFormat.amount(140_000.0))
        assertEquals("900M", CuratorFormat.amount(900_000_000.0))
        assertEquals("3.1M", CuratorFormat.amount(3_100_000.0))
        assertEquals("9.3k", CuratorFormat.amount(9280.0))
        assertEquals("2B", CuratorFormat.amount(2e9))
        assertEquals("13", CuratorFormat.amount(12.5))
        assertEquals("2.5", CuratorFormat.amount(2.5))
        assertEquals("5", CuratorFormat.amount(5.0))
        assertEquals("17,440", CuratorFormat.full(Column.NPC, synth))
    }

    @Test
    fun `arrows sit after the value`() {
        assertEquals(" ↑", CuratorFormat.arrow(Feedback(Match.NONE, Arrow.UP), narrow = false))
        assertEquals("↓", CuratorFormat.arrow(Feedback(Match.NONE, Arrow.DOWN), narrow = true))
        assertEquals("", CuratorFormat.arrow(Feedback(Match.EXACT), narrow = false))
    }

    @Test
    fun `shared results use one row of squares per guess`() {
        val feedback = Column.entries.associateWith { Feedback(Match.EXACT) }
        val partial =
            feedback +
                (Column.STAGE to Feedback(Match.PARTIAL)) +
                (Column.NPC to Feedback(Match.NONE, Arrow.UP))
        val day =
            CuratorDay(
                "2027-01-15",
                12,
                RoundState.SOLVED,
                10,
                0,
                listOf(
                    CuratorGuess("A", "A", synth, partial, false),
                    CuratorGuess("B", "B", synth, feedback, false),
                ),
                null,
                null,
            )
        assertEquals(
            "Curator #12 2/10\n🟩🟩🟩🟨🟩🟩🟩🟩⬛🟩\n🟩🟩🟩🟩🟩🟩🟩🟩🟩🟩",
            CuratorFormat.share(day),
        )
        assertTrue(
            CuratorFormat.share(day.copy(state = RoundState.FAILED)).startsWith("Curator #12 X/10")
        )
    }
}
