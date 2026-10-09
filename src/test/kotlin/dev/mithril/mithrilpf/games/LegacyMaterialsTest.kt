package dev.mithril.mithrilpf.games

import kotlin.test.*

class LegacyMaterialsTest {
    @Test
    fun `old material names map to current item ids`() {
        assertEquals("paper", LegacyMaterials.itemId("PAPER", null))
        assertEquals("golden_sword", LegacyMaterials.itemId("GOLD_SWORD", null))
        assertEquals("wooden_shovel", LegacyMaterials.itemId("WOOD_SPADE", null))
        assertEquals("gold_ingot", LegacyMaterials.itemId("GOLD_INGOT", null))
        assertEquals("diamond_shovel", LegacyMaterials.itemId("DIAMOND_SPADE", null))
        assertEquals("player_head", LegacyMaterials.itemId("SKULL_ITEM", 3))
        assertEquals("lapis_lazuli", LegacyMaterials.itemId("INK_SACK", 4))
        assertEquals("pufferfish", LegacyMaterials.itemId("RAW_FISH", 3))
        assertEquals(
            "light_blue_stained_glass_pane",
            LegacyMaterials.itemId("STAINED_GLASS_PANE", 3),
        )
        assertEquals("lead", LegacyMaterials.itemId("LEASH", null))
        assertEquals("quartz_stairs", LegacyMaterials.itemId("QUARTZ_STAIRS", null))
    }

    @Test
    fun `out of range variants and unreadable names fall back`() {
        assertEquals("skeleton_skull", LegacyMaterials.itemId("SKULL_ITEM", 9))
        assertEquals("white_wool", LegacyMaterials.itemId("WOOL", 99))
        assertEquals(LegacyMaterials.FALLBACK, LegacyMaterials.itemId(null, null))
        assertEquals(LegacyMaterials.FALLBACK, LegacyMaterials.itemId("../evil", null))
    }
}
