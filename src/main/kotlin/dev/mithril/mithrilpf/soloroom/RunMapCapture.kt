package dev.mithril.mithrilpf.soloroom

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.mithril.mithrilpf.soloclear.SoloClearState

/**
 * Client-thread observations. Only a structured, immutable completion snapshot leaves the client.
 */
class RunMapCapture {
    private val secrets = arrayOfNulls<Pair<Int, Int>>(36)
    private val visited = mutableSetOf<Int>()

    fun visit(tile: Int) {
        visited.add(tile)
    }

    fun observeSecrets(tile: Int?, text: String) {
        if (tile == null || tile !in 0..35) return
        val match = secretCounter.find(text) ?: return
        val found = match.groupValues[1].toInt()
        val total = match.groupValues[2].toInt()
        if (total > 100 || found > total) return
        secrets[tile] = found to total
    }

    fun snapshot(
        map: SoloRoomMap?,
        colors: ByteArray?,
        definitions: List<SoloRoomDefinition?>,
    ): JsonObject? {
        if (map == null || colors?.size != 16384 || definitions.size != 36) return null
        val grid = MapGrid(map, colors)
        val present = (0..35).filter { grid.center(it) != 0 || definitions[it] != null }.toSet()
        if (present.isEmpty()) return null
        val groups = mutableListOf<Set<Int>>()
        val remaining = present.toMutableSet()
        while (remaining.isNotEmpty()) {
            val group = mutableSetOf(remaining.first())
            val pending = ArrayDeque(group)
            while (pending.isNotEmpty()) {
                val tile = pending.removeFirst()
                neighbors(tile)
                    .filter { it in present && grid.connected(tile, it) }
                    .forEach {
                        if (group.add(it)) pending.addLast(it)
                    }
            }
            if (group.size > 4) return null
            groups.add(group)
            remaining.removeAll(group)
        }
        val rooms = JsonArray()
        groups.forEach { group -> rooms.add(room(group, grid, definitions)) }
        val doors = JsonArray()
        val owners = groups.flatMapIndexed { index, group -> group.map { it to index } }.toMap()
        present.sorted().forEach { a ->
            neighbors(a)
                .filter { it > a && it in present && owners[a] != owners[it] }
                .forEach { b ->
                    grid.door(a, b)?.let { type ->
                        doors.add(
                            JsonObject().apply {
                                addProperty("a", a)
                                addProperty("b", b)
                                addProperty("type", type)
                            }
                        )
                    }
                }
        }
        return JsonObject().apply {
            addProperty("version", 1)
            add("rooms", rooms)
            add("doors", doors)
        }
    }

    private fun room(
        tiles: Set<Int>,
        grid: MapGrid,
        definitions: List<SoloRoomDefinition?>,
    ): JsonObject {
        val definition = tiles.firstNotNullOfOrNull { definitions[it] }
        val type = definition?.type ?: grid.type(tiles.first())
        val states = tiles.map { grid.state(it, type) }
        val state =
            listOf("FAILED", "COMPLETE", "CLEARED", "DISCOVERED", "UNOPENED").firstOrNull {
                it in states
            } ?: "UNKNOWN"
        val observed =
            tiles
                .mapNotNull { secrets[it] }
                .filter { definition == null || it.second == definition.secrets }
                .maxByOrNull { it.first }
        val total = definition?.secrets ?: observed?.second
        val found =
            when {
                state == "COMPLETE" || total == 0 -> total
                observed != null -> observed.first
                tiles.none { it in visited } -> 0
                else -> null
            }
        return JsonObject().apply {
            add("tiles", JsonArray().apply { tiles.sorted().forEach(::add) })
            addProperty("name", definition?.name)
            addProperty("type", type)
            addProperty("state", state)
            addProperty("secrets_found", found)
            addProperty("secrets_total", total)
        }
    }

    companion object {
        fun active(inDungeon: Boolean, enabled: Boolean, solo: SoloClearState?) =
            inDungeon && enabled && solo?.active == true

        fun canTrack(
            eligible: Boolean,
            captureSolo: Boolean,
            tile: Int?,
            dead: Boolean,
            spectator: Boolean,
        ) = (eligible || captureSolo) && tile != null && !dead && !spectator

        private val secretCounter =
            Regex(
                "(?<!\\d)(\\d{1,3})\\s*/\\s*(\\d{1,3})\\s+Secrets?\\b",
                RegexOption.IGNORE_CASE,
            )

        fun neighbors(tile: Int): List<Int> =
            listOfNotNull(
                (tile - 6).takeIf { it >= 0 },
                (tile + 6).takeIf { it < 36 },
                (tile - 1).takeIf { tile % 6 > 0 },
                (tile + 1).takeIf { tile % 6 < 5 },
            )
    }
}

/** Hotbar tile/door colour rules adapted from NoammAddons (CC0); no pixels are uploaded. */
private class MapGrid(private val map: SoloRoomMap, private val colors: ByteArray) {
    private fun pixel(x: Int, z: Int): Int =
        if (x in 0..127 && z in 0..127) colors[z * 128 + x].toInt().and(255) else 0

    private fun edge(a: Int, b: Int, side: Boolean = false): Int {
        val x = map.cornerX + map.size / 2 + (a % 6 + b % 6) * (map.size + 4) / 2
        val z = map.cornerZ + map.size / 2 + (a / 6 + b / 6) * (map.size + 4) / 2
        return pixel(
            x - if (side && a / 6 != b / 6) 4 else 0,
            z - if (side && a / 6 == b / 6) 4 else 0,
        )
    }

    fun center(tile: Int) = map.color(colors, tile)

    fun connected(a: Int, b: Int) = edge(a, b) != 0 && edge(a, b, true) != 0

    fun door(a: Int, b: Int): String? =
        when (edge(a, b)) {
            18 -> "BLOOD"
            30 -> "ENTRANCE"
            119 -> "WITHER"
            74,
            82,
            66,
            62,
            85,
            63 -> "NORMAL"
            else -> null
        }

    fun type(tile: Int): String =
        when (map.color(colors, tile, true)) {
            18 -> "BLOOD"
            82 -> "FAIRY"
            34 -> "RARE"
            74 -> "CHAMPION"
            66 -> "PUZZLE"
            62 -> "TRAP"
            63,
            85 -> "NORMAL"
            30 -> "ENTRANCE"
            else -> "UNKNOWN"
        }

    fun state(tile: Int, type: String): String =
        when (center(tile)) {
            0 -> "UNKNOWN"
            18 -> if (type == "PUZZLE") "FAILED" else "DISCOVERED"
            30 -> if (type == "ENTRANCE") "DISCOVERED" else "COMPLETE"
            34 -> "CLEARED"
            85,
            119 -> "UNOPENED"
            else -> "DISCOVERED"
        }
}
