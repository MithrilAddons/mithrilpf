package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.dungeontimer.DungeonBest
import dev.mithril.mithrilpf.dungeontimer.DungeonSplit
import dev.mithril.mithrilpf.dungeontimer.DungeonTimerState
import dev.mithril.mithrilpf.dungeontimer.SplitTime
import java.util.Locale

enum class RecordsView(val key: String) {
    OVERVIEW("overview"),
    SPLITS("splits"),
    ROOMS("rooms"),
}

enum class SplitKind {
    SPLIT,
    TOTAL,
    SOLO,
}

/** A split on the floor; [best] is null until it has a PB. */
data class SplitRow(val name: String, val best: DungeonBest?, val kind: SplitKind = SplitKind.SPLIT)

/** A room's PBs on one floor; secrets PBs are stored as the time per secret. */
data class RoomRow(
    val name: String,
    val midclear: DungeonBest? = null,
    val regular: DungeonBest? = null,
    val secretsMid: DungeonBest? = null,
    val secretsReg: DungeonBest? = null,
    val total: DungeonBest? = null,
    /** A room without secrets saves one clear, shown under Regular. */
    val noSecrets: Boolean = false,
)

/** The player's saved PBs (by kind, floor and name), arranged for the Records views. */
class RecordsData(
    private val records: Map<String, Map<String, Map<String, DungeonBest>>>,
    private val definitions: Map<String, List<DungeonSplit>>,
) {
    fun hasPbs(floor: String) = records.any { (kind, floors) ->
        floors[floor].orEmpty().keys.any { kind != "rooms" || ROOM_KEY.matches(it) }
    }

    fun best(kind: String, floor: String, name: String): DungeonBest? =
        records[kind]?.get(floor)?.get(name)

    /**
     * Run splits in run order, then the floor's own splits, then any other saved split, Total and,
     * on F7 and M7 or wherever one is saved, the solo 300-score clear.
     */
    fun splits(floor: String): List<SplitRow> {
        val saved = records["splits"]?.get(floor).orEmpty()
        val order =
            RUN_SPLITS + definitions[floor].orEmpty().map { DungeonTimerState.clean(it.name) }
        val names = order + saved.keys.filter { it !in order && it != TOTAL }
        val rows = names.distinct().map { SplitRow(it, saved[it]) }.toMutableList()
        rows += SplitRow(TOTAL, saved[TOTAL], SplitKind.TOTAL)
        val solo = best("solo", floor, SOLO)
        if (floor in SOLO_FLOORS || solo != null) rows += SplitRow(SOLO, solo, SplitKind.SOLO)
        return rows
    }

    /** Rooms with a PB on [floor] whose name contains [query], sorted by name. */
    fun rooms(floor: String, query: String = ""): List<RoomRow> {
        val rows = sortedMapOf<String, RoomRow>(String.CASE_INSENSITIVE_ORDER)
        for ((key, best) in records["rooms"]?.get(floor).orEmpty()) {
            val (room, part, style) = ROOM_KEY.matchEntire(key)?.destructured ?: continue
            val row = rows[room] ?: RoomRow(room)
            rows[room] =
                when ("$part $style".trim()) {
                    "Clear Midclear" -> row.copy(midclear = best)
                    "Clear Regular" -> row.copy(regular = best)
                    "Clear" -> row.copy(regular = best, noSecrets = true)
                    "Secrets Midclear" -> row.copy(secretsMid = best)
                    "Secrets Regular" -> row.copy(secretsReg = best)
                    "Total" -> row.copy(total = best)
                    else -> row
                }
        }
        val needle = query.trim()
        return rows.values.filter { it.name.contains(needle, ignoreCase = true) }
    }

    companion object {
        const val TOTAL = "Total"
        const val SOLO = "300 Score"
        private val RUN_SPLITS = listOf("Blood Open", "Watcher Clear", "Portal", "Boss Entry")
        private val SOLO_FLOORS = setOf("F7", "M7")

        // Clears saved before styles were tracked ("<room> · Cleared") are not shown.
        private val ROOM_KEY = Regex("(.+) · (Clear|Secrets|Total)(?: · (Midclear|Regular))?")

        /** Secrets per minute from a saved time per secret. */
        fun rate(perSecretMillis: Long): String =
            String.format(Locale.ROOT, "%.1f/min", 60_000.0 / perSecretMillis.coerceAtLeast(1))

        fun time(best: DungeonBest) = SplitTime(best.realMillis, best.ticks)
    }
}

/**
 * A room table's value columns: right edges relative to the table. Wide tables show both styles
 * (Midclear, Regular, Secrets · mid, Secrets · reg, Total); narrow ones show Clear, Secrets and
 * Total for the style picked above the table.
 */
data class RoomColumns(val both: Boolean, val rights: List<Int>, val nameWidth: Int) {
    companion object {
        private val WIDE = listOf(66, 66, 82, 82, 66)
        private val NARROW = listOf(60, 58, 60)
        private const val PAD = 8
        const val MIN_BOTH = 468

        fun fit(width: Int): RoomColumns {
            val both = width >= MIN_BOTH
            val widths = if (both) WIDE else NARROW
            val rights = mutableListOf<Int>()
            var right = width - PAD
            for (w in widths.asReversed()) {
                rights += right
                right -= w
            }
            return RoomColumns(both, rights.asReversed(), (right - PAD).coerceAtLeast(0))
        }
    }
}

/** The open floor list: Entrance across the top, then F1–F7 beside M1–M7. */
data class FloorPicker(val x: Int, val y: Int) {
    data class Cell(val x: Int, val y: Int, val width: Int, val height: Int)

    fun cell(floor: String): Cell? {
        val column = (WIDTH - 12) / 2
        return when {
            floor == "E" -> Cell(x + 4, y + 4, WIDTH - 8, ROW)
            Regex("[FM][1-7]").matches(floor) ->
                Cell(
                    x + if (floor[0] == 'F') 4 else 8 + column,
                    y + FIRST + (floor[1].digitToInt() - 1) * ROW,
                    column,
                    ROW,
                )
            else -> null
        }
    }

    fun contains(mouseX: Double, mouseY: Double) =
        mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT

    fun floorAt(mouseX: Double, mouseY: Double): String? = FLOORS.firstOrNull { floor ->
        cell(floor)!!.let {
            mouseX >= it.x &&
                mouseX < it.x + it.width &&
                mouseY >= it.y &&
                mouseY < it.y + it.height
        }
    }

    companion object {
        const val WIDTH = 148
        const val ROW = 16
        const val HEADERS = 26
        private const val FIRST = 40
        const val HEIGHT = FIRST + 7 * ROW + 4
        val FLOORS = listOf("E") + (1..7).map { "F$it" } + (1..7).map { "M$it" }

        /** Below the button, or above it when the list would leave the screen. */
        fun below(buttonX: Int, buttonY: Int, buttonHeight: Int, screenHeight: Int): FloorPicker {
            val under = buttonY + buttonHeight + 2
            val y = if (under + HEIGHT <= screenHeight) under else buttonY - 2 - HEIGHT
            return FloorPicker(buttonX, y.coerceAtLeast(0))
        }
    }
}
