package dev.mithril.mithrilpf.games

import java.util.Locale

/**
 * Hypixel's items list still names Minecraft 1.8 materials. This maps the common ones to current
 * item IDs for the answer's icon; anything unknown falls back to paper.
 */
object LegacyMaterials {
    const val FALLBACK = "paper"

    private val TOOL =
        Regex("(gold|wood)_(sword|pickaxe|axe|shovel|hoe|helmet|chestplate|leggings|boots)")

    private val COLORS =
        listOf(
            "white",
            "orange",
            "magenta",
            "light_blue",
            "yellow",
            "lime",
            "pink",
            "gray",
            "light_gray",
            "cyan",
            "purple",
            "blue",
            "brown",
            "green",
            "red",
            "black",
        )
    private val DYES =
        listOf(
            "ink_sac",
            "red_dye",
            "green_dye",
            "cocoa_beans",
            "lapis_lazuli",
            "purple_dye",
            "cyan_dye",
            "light_gray_dye",
            "gray_dye",
            "pink_dye",
            "lime_dye",
            "yellow_dye",
            "light_blue_dye",
            "magenta_dye",
            "orange_dye",
            "bone_meal",
        )
    private val SKULLS =
        listOf(
            "skeleton_skull",
            "wither_skeleton_skull",
            "zombie_head",
            "player_head",
            "creeper_head",
            "dragon_head",
        )
    private val FISH = listOf("cod", "salmon", "tropical_fish", "pufferfish")
    private val COLORED =
        mapOf(
            "WOOL" to "wool",
            "CARPET" to "carpet",
            "STAINED_CLAY" to "terracotta",
            "STAINED_GLASS" to "stained_glass",
            "STAINED_GLASS_PANE" to "stained_glass_pane",
        )
    private val RENAMED =
        mapOf(
            "LEASH" to "lead",
            "EXP_BOTTLE" to "experience_bottle",
            "WATCH" to "clock",
            "SULPHUR" to "gunpowder",
            "FIREWORK" to "firework_rocket",
            "FIREWORK_CHARGE" to "firework_star",
            "EYE_OF_ENDER" to "ender_eye",
            "CARROT_ITEM" to "carrot",
            "POTATO_ITEM" to "potato",
            "NETHER_STALK" to "nether_wart",
            "SPECKLED_MELON" to "glistering_melon_slice",
            "MELON" to "melon_slice",
            "RAW_BEEF" to "beef",
            "PORK" to "porkchop",
            "RAW_CHICKEN" to "chicken",
            "SEEDS" to "wheat_seeds",
            "BOOK_AND_QUILL" to "writable_book",
            "MUSHROOM_SOUP" to "mushroom_stew",
            "WATER_LILY" to "lily_pad",
            "RED_ROSE" to "poppy",
            "YELLOW_FLOWER" to "dandelion",
            "DOUBLE_PLANT" to "sunflower",
            "SNOW_BALL" to "snowball",
            "COOKED_FISH" to "cooked_cod",
            "BOAT" to "oak_boat",
            "FIREBALL" to "fire_charge",
            "SAPLING" to "oak_sapling",
            "LEAVES" to "oak_leaves",
            "LONG_GRASS" to "short_grass",
            "WEB" to "cobweb",
            "EMPTY_MAP" to "map",
            "MAP" to "filled_map",
            "NETHER_BRICK_ITEM" to "nether_brick",
            "TRAP_DOOR" to "oak_trapdoor",
            "FENCE" to "oak_fence",
            "WORKBENCH" to "crafting_table",
            "ENDER_PORTAL_FRAME" to "end_portal_frame",
            "ENCHANTMENT_TABLE" to "enchanting_table",
            "REDSTONE_TORCH_ON" to "redstone_torch",
            "SIGN" to "oak_sign",
            "BED" to "red_bed",
            "IRON_BARDING" to "iron_horse_armor",
            "GOLD_BARDING" to "golden_horse_armor",
            "DIAMOND_BARDING" to "diamond_horse_armor",
            "SKULL" to "skeleton_skull",
        )

    fun itemId(material: String?, durability: Int?): String {
        val name = material?.uppercase(Locale.ROOT)?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) }
        val variant = durability ?: 0
        return when {
            name == null -> FALLBACK
            name == "SKULL_ITEM" -> SKULLS.getOrNull(variant) ?: "skeleton_skull"
            name == "INK_SACK" -> DYES.getOrNull(variant) ?: "ink_sac"
            name == "RAW_FISH" -> FISH.getOrNull(variant) ?: "cod"
            name in COLORED -> "${COLORS.getOrNull(variant) ?: "white"}_${COLORED.getValue(name)}"
            name in RENAMED -> RENAMED.getValue(name)
            else -> {
                val id = name.lowercase(Locale.ROOT).replace(Regex("_spade$"), "_shovel")
                // Only gold and wood tools and armour gained the "-en" suffix.
                TOOL.matchEntire(id)?.let { "${it.groupValues[1]}en_${it.groupValues[2]}" } ?: id
            }
        }
    }
}
