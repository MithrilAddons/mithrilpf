package dev.mithril.mithrilpf.soloroom

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

@Serializable
data class SoloRoomDefinition(
    val name: String,
    val type: String,
    val cores: List<Int>,
    val secrets: Int,
) {
    val tracked
        get() =
            type == "NORMAL" ||
                type == "RARE" ||
                type == "CHAMPION" ||
                type == "PUZZLE" ||
                type == "TRAP"
}

/** Map calibration/marker rules adapted from NoammAddons (CC0); no rendering or world access. */
data class SoloRoomMap(val cornerX: Int, val cornerZ: Int, val size: Int) {
    fun color(colors: ByteArray, tile: Int, corner: Boolean = false): Int {
        val x = cornerX + (tile % 6) * (size + 4) + if (corner) 0 else size / 2
        val z = cornerZ + (tile / 6) * (size + 4) + if (corner) 0 else size / 2
        if (x !in 0..127 || z !in 0..127) return 0
        return colors.getOrNull(z * 128 + x)?.toInt()?.and(255) ?: 0
    }

    /**
     * Tiles of the player markers other than yours. Your own marker is the one closest to your
     * position; markers use the map decoration grid, two units per pixel, centred on 0.
     */
    fun teammateTiles(markers: List<Pair<Byte, Byte>>, selfX: Double, selfZ: Double): Set<Int> {
        fun unit(world: Double, corner: Int) =
            (corner + size / 2.0 + (world + 185) / 32 * (size + 4)) * 2 - 128
        val x = unit(selfX, cornerX)
        val z = unit(selfZ, cornerZ)
        val others = markers.toMutableList()
        others
            .minByOrNull { (mx, mz) -> (mx - x) * (mx - x) + (mz - z) * (mz - z) }
            ?.let(others::remove)
        return others.mapNotNull { (mx, mz) -> markerTile(mx, mz) }.toSet()
    }

    private fun markerTile(x: Byte, z: Byte): Int? {
        fun cell(unit: Byte, corner: Int) =
            (((unit + 128) / 2.0 - corner - size / 2.0) / (size + 4)).roundToInt()
        val column = cell(x, cornerX)
        val row = cell(z, cornerZ)
        if (column !in 0..5 || row !in 0..5) return null
        return row * 6 + column
    }

    companion object {
        fun calibrate(colors: ByteArray, floor: String): SoloRoomMap? {
            if (colors.size != 16384 || !Regex("E|[FM][1-7]").matches(floor)) return null
            for (z in 0..127) {
                var x = 0
                while (x < 128) {
                    if (colors[z * 128 + x].toInt() != 30) {
                        x++
                        continue
                    }
                    val start = x
                    while (x < 128 && colors[z * 128 + x].toInt() == 30) x++
                    val size = x - start
                    if (size !in setOf(16, 18)) continue
                    val corner =
                        when (floor.last()) {
                            'E' -> 22 to 22
                            '1' -> 22 to 11
                            '2',
                            '3' -> 11 to 11
                            else -> start % (size + 4) to z % (size + 4)
                        }
                    return SoloRoomMap(corner.first, corner.second, size)
                }
            }
            return null
        }

        fun tile(x: Double, z: Double): Int? {
            val gridX = ((x + 185) / 32).roundToInt()
            val gridZ = ((z + 185) / 32).roundToInt()
            if (gridX !in 0..5 || gridZ !in 0..5) return null
            return gridZ * 6 + gridX
        }

        private val ignored =
            setOf(
                "minecraft:chest",
                "minecraft:trapped_chest",
                "minecraft:piston_head",
                "minecraft:moving_piston",
                "minecraft:water",
                "minecraft:lava",
                "minecraft:fire",
                "minecraft:soul_fire",
            )

        fun coreToken(name: String): Int =
            (if (name in ignored || name.endsWith("_planks")) "minecraft:air" else name).hashCode()
    }
}
