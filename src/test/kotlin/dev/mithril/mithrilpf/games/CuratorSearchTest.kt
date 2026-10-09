package dev.mithril.mithrilpf.games

import kotlin.test.*

class CuratorSearchTest {
    private val items =
        listOf(
            CatalogItem("SYNTHESIZER_V1", "Synthesizer v1"),
            CatalogItem("SYNTHESIZER_V2", "Synthesizer v2"),
            CatalogItem("SYNTHESIZER_V3", "Synthesizer v3"),
            CatalogItem("ASPECT_OF_THE_END", "Aspect of the End"),
            CatalogItem("ENDER_BOW", "End Stone Bow"),
            CatalogItem("TALISMAN", "Bat Talisman"),
        )

    @Test
    fun `names match from the start of any word, name starts first`() {
        assertEquals(
            listOf("End Stone Bow", "Aspect of the End"),
            CuratorSearch.suggestions(items, " END ", emptySet()).map { it.name },
        )
        assertEquals(
            listOf("Synthesizer v1", "Synthesizer v3"),
            CuratorSearch.suggestions(items, "synth", setOf("SYNTHESIZER_V2")).map { it.name },
        )
        assertTrue(CuratorSearch.suggestions(items, "", emptySet()).isEmpty())
        assertTrue(CuratorSearch.suggestions(items, "isman", emptySet()).isEmpty())
    }

    @Test
    fun `at most six suggestions`() {
        val many = (1..10).map { CatalogItem("ITEM_$it", "Item $it") }
        assertEquals(CuratorSearch.LIMIT, CuratorSearch.suggestions(many, "item", emptySet()).size)
    }

    @Test
    fun `a whole name ignoring case picks that item`() {
        assertEquals("TALISMAN", CuratorSearch.exact(items, " bat talisman ")?.id)
        assertNull(CuratorSearch.exact(items, "bat"))
    }
}
