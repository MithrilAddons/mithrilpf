package dev.mithril.mithrilpf.games

import java.util.Locale
import kotlin.math.abs

/** Cell text for the guess grid: short for wide cells, shorter still for narrow ones. */
object CuratorFormat {
    private val RARITY =
        mapOf(
            "COMMON" to ("COM" to "C"),
            "UNCOMMON" to ("UNC" to "U"),
            "RARE" to ("RARE" to "R"),
            "EPIC" to ("EPIC" to "E"),
            "LEGENDARY" to ("LEG" to "L"),
            "MYTHIC" to ("MYTH" to "M"),
            "DIVINE" to ("DIV" to "D"),
            "SUPREME" to ("DIV" to "D"),
            "SPECIAL" to ("SPEC" to "S"),
            "VERY_SPECIAL" to ("VSPEC" to "VS"),
        )
    private val TYPE =
        mapOf(
            "ACCESSORY" to ("Acc." to "Acc"),
            "NECKLACE" to ("Neck." to "Nck"),
            "SWORD" to ("Sword" to "Swd"),
            "LONGSWORD" to ("Long" to "Lng"),
            "HELMET" to ("Helm." to "Hlm"),
            "CHESTPLATE" to ("Chest" to "Cst"),
            "LEGGINGS" to ("Legs" to "Leg"),
            "BOOTS" to ("Boots" to "Bts"),
            "BRACELET" to ("Brace." to "Brc"),
            "GLOVES" to ("Gloves" to "Glv"),
            "FARMING_TOOL" to ("Farm" to "Frm"),
            "FISHING_ROD" to ("Rod" to "Rod"),
            "PICKAXE" to ("Pick" to "Pck"),
            "REFORGE_STONE" to ("Stone" to "Stn"),
            "PET_ITEM" to ("Pet" to "Pet"),
        )
    private val MUSEUM =
        mapOf(
            "COMBAT" to ("Combat" to "Cmb"),
            "DUNGEONEERING" to ("Dung." to "Dng"),
            "FARMING" to ("Farm." to "Frm"),
            "FISHING" to ("Fish." to "Fsh"),
            "MINING" to ("Mining" to "Min"),
            "FORAGING" to ("Forag." to "For"),
            "HUNTING" to ("Hunt." to "Hnt"),
            "SPECIAL" to ("Special" to "Spc"),
        )
    private val STAGE =
        mapOf(
            "STARTER" to ("Start" to "St"),
            "AMATEUR" to ("Amat." to "Am"),
            "INTERMEDIATE" to ("Inter." to "In"),
            "SKILLED" to ("Skill." to "Sk"),
            "EXPERT" to ("Exp." to "Ex"),
            "PROFESSIONAL" to ("Pro." to "Pr"),
            "MASTER" to ("Mast." to "Ma"),
        )
    private val SKILL =
        mapOf(
            "COMBAT" to "Cmb",
            "MINING" to "Min",
            "FARMING" to "Frm",
            "FISHING" to "Fsh",
            "FORAGING" to "For",
            "ENCHANTING" to "Ench",
            "ALCHEMY" to "Alch",
            "TAMING" to "Tam",
            "CARPENTRY" to "Carp",
            "RUNECRAFTING" to "Rune",
            "SOCIAL" to "Soc",
            "HUNTING" to "Hnt",
        )
    private val SLAYER =
        mapOf(
            "ZOMBIE" to "Rev",
            "SPIDER" to "Tara",
            "WOLF" to "Sven",
            "ENDERMAN" to "Eman",
            "BLAZE" to "Blaze",
            "VAMPIRE" to "Vamp",
        )
    // Requirements shown in a cell, most recognisable first.
    private val PRIORITY =
        listOf("SKILL", "DUNGEON_SKILL", "DUNGEON_TIER", "SLAYER", "HEART_OF_THE_MOUNTAIN")

    fun cell(column: Column, values: ClueValues, narrow: Boolean): String =
        when (column) {
            Column.RARITY -> pick(RARITY, values.rarity, narrow)
            Column.TYPE -> pick(TYPE, values.type, narrow)
            Column.MUSEUM -> pick(MUSEUM, values.museum, narrow)
            Column.STAGE -> pick(STAGE, values.stage, narrow)
            Column.REQUIREMENTS -> requirement(values.requirements, narrow)
            Column.SOULBOUND ->
                when (values.soulbound) {
                    null -> "No"
                    "COOP" -> if (narrow) "Co" else "Co-op"
                    else -> words(values.soulbound)
                }
            Column.ORIGIN -> values.origin?.let(::words) ?: "—"
            Column.MARKET -> values.market?.let(::amount) ?: "—"
            Column.NPC -> values.npc?.let(::amount) ?: "—"
            Column.LENGTH -> values.length.toString()
        }

    fun arrow(feedback: Feedback, narrow: Boolean) =
        when (feedback.arrow) {
            null -> ""
            Arrow.UP -> if (narrow) "↑" else " ↑"
            Arrow.DOWN -> if (narrow) "↓" else " ↓"
        }

    /** The value in full, for tooltips. */
    fun full(column: Column, values: ClueValues): String =
        when (column) {
            Column.REQUIREMENTS ->
                values.requirements.entries
                    .joinToString(", ") { (key, level) ->
                        listOfNotNull(requirementName(key), level?.let(::number)).joinToString(" ")
                    }
                    .ifEmpty { "None" }
            Column.SOULBOUND ->
                values.soulbound?.let { if (it == "COOP") "Co-op" else words(it) } ?: "No"
            Column.MARKET -> values.market?.let(::number) ?: "Not tradeable"
            Column.NPC -> values.npc?.let(::number) ?: "None"
            Column.LENGTH -> values.length.toString()
            Column.RARITY -> values.rarity?.let(::words) ?: "None"
            Column.TYPE -> values.type?.let(::words) ?: "None"
            Column.MUSEUM -> values.museum?.let(::words) ?: "Not in museum"
            Column.STAGE -> values.stage?.let(::words) ?: "None"
            Column.ORIGIN -> values.origin?.let(::words) ?: "None"
        }

    /** "1.2M", "140k", "17.4k": one decimal below ten, none above. */
    fun amount(value: Double): String {
        val (scaled, suffix) =
            when {
                abs(value) >= 1e9 -> value / 1e9 to "B"
                abs(value) >= 1e6 -> value / 1e6 to "M"
                abs(value) >= 1e3 -> value / 1e3 to "k"
                else -> value to ""
            }
        val text =
            if (abs(scaled) < 100 && suffix.isNotEmpty() || abs(scaled) < 10)
                String.format(Locale.ROOT, "%.1f", scaled).removeSuffix(".0")
            else String.format(Locale.ROOT, "%.0f", scaled)
        return text + suffix
    }

    /** Wordle-style squares for sharing outside Minecraft; never sent to Hypixel chat. */
    fun share(day: CuratorDay): String {
        val score = if (day.state == RoundState.SOLVED) day.guesses.size.toString() else "X"
        val rows =
            day.guesses.map { guess ->
                Column.entries.joinToString("") { column ->
                    when (guess.feedback.getValue(column).match) {
                        Match.EXACT -> "🟩"
                        Match.PARTIAL -> "🟨"
                        Match.NONE -> "⬛"
                    }
                }
            }
        return (listOf("Curator #${day.number} $score/${day.limit}") + rows).joinToString("\n")
    }

    private fun pick(map: Map<String, Pair<String, String>>, value: String?, narrow: Boolean) =
        when (value) {
            null -> "—"
            in map -> map.getValue(value).let { if (narrow) it.second else it.first }
            else -> words(value).let { if (narrow) it.take(3) else it.take(6) }
        }

    private fun requirement(requirements: Map<String, Int?>, narrow: Boolean): String {
        if (requirements.isEmpty()) return "—"
        val key =
            requirements.keys.minBy { key ->
                PRIORITY.indexOf(key.substringBefore(':')).let { if (it < 0) PRIORITY.size else it }
            }
        val level = requirements[key]
        val short = requirementShort(key)
        val more = if (requirements.size > 1) "+" else ""
        return when {
            level == null -> short + more
            narrow -> short.take(1) + level + more
            else -> "$short $level$more"
        }
    }

    private fun requirementShort(key: String): String {
        val (kind, qualifier) = key.substringBefore(':') to key.substringAfter(':', "")
        return when (kind) {
            "SKILL" -> SKILL[qualifier] ?: words(qualifier).take(4)
            "DUNGEON_SKILL" -> "Cata"
            "DUNGEON_TIER" -> "Floor"
            "SLAYER" -> SLAYER[qualifier] ?: "Slay"
            "HEART_OF_THE_MOUNTAIN" -> "HotM"
            "GARDEN_LEVEL" -> "Gdn"
            "COLLECTION" -> "Coll"
            "CRIMSON_ISLE_REPUTATION" -> "Rep"
            "KUUDRA_COMPLETION" -> "Kuudra"
            else -> words(kind).substringBefore(' ').take(5)
        }
    }

    private fun requirementName(key: String): String {
        val (kind, qualifier) = key.substringBefore(':') to key.substringAfter(':', "")
        return when (kind) {
            "SKILL" -> words(qualifier)
            "DUNGEON_SKILL" -> "Catacombs"
            "DUNGEON_TIER" -> "Floor"
            "SLAYER" -> "${words(qualifier)} Slayer"
            "HEART_OF_THE_MOUNTAIN" -> "HotM"
            else ->
                listOf(words(kind), words(qualifier)).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    private fun number(value: Number) =
        if (value.toDouble() % 1.0 == 0.0) String.format(Locale.ROOT, "%,d", value.toLong())
        else String.format(Locale.ROOT, "%,.1f", value.toDouble())

    private fun words(value: String) =
        value
            .split('_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { part ->
                part.lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
}
