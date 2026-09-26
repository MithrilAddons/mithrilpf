package dev.mithril.mithrilpf.soloroom

import dev.mithril.mithrilpf.dungeontimer.SplitTime
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import java.util.IdentityHashMap
import kotlinx.serialization.json.Json
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.saveddata.maps.MapId

/** Client-thread only. Maximum two loaded 129-block columns per tick; no network lookup. */
class RoomDetector {
    private val rooms = arrayOfNulls<SoloRoomDefinition>(36)
    private val retry = IntArray(36)
    private val tokens = IdentityHashMap<Block, Int>()
    private var calibration: SoloRoomMap? = null
    private var nextCalibration = 0
    private var ticks = 0
    private var scan = 0
    var mapId: MapId? = null

    fun tick(
        client: Minecraft,
        state: SoloRoomState,
        floor: String,
        now: TimerStamp,
        result: (String, Map<String, SplitTime>) -> Unit,
    ) {
        val player = client.player ?: return
        val tile = SoloRoomMap.tile(player.x, player.z)
        if (!state.eligible || tile == null || player.isDeadOrDying || player.isSpectator) {
            state.leave(now)
            return
        }
        ticks++
        identify(client, tile)
        identify(client, scan++ % 36)
        val id = player.inventory.getItem(8).get(DataComponents.MAP_ID) ?: mapId
        val colors = id?.let { client.level?.getMapData(it)?.colors }
        if (colors != null && calibration == null && ticks >= nextCalibration) {
            nextCalibration = ticks + 20
            calibration = SoloRoomMap.calibrate(colors, floor)
        }
        val map = calibration
        val room = rooms[tile]
        val color = if (map != null && colors != null) map.color(colors, tile) else 0
        if (room != null && room.tracked && color != 0 && color != 18) {
            state.enter(
                room.name,
                color == 34 || color == 30,
                color == 30 || room.secrets == 0,
                now,
            )
        } else state.leave(now)
        if (map == null || colors == null) return
        rooms.forEachIndexed { index, definition ->
            if (definition == null || !definition.tracked) return@forEachIndexed
            val marker = map.color(colors, index)
            if (marker == 34 || marker == 30) {
                val times =
                    state.observe(
                        definition.name,
                        true,
                        marker == 30 && definition.secrets > 0,
                        now,
                    )
                if (times.isNotEmpty()) result(definition.name, times)
            }
        }
    }

    private fun identify(client: Minecraft, tile: Int) {
        if (rooms[tile] != null || ticks < retry[tile]) return
        retry[tile] = ticks + 20
        val x = -185 + tile % 6 * 32
        val z = -185 + tile / 6 * 32
        val level = client.level ?: return
        if (!level.hasChunk(x shr 4, z shr 4)) return
        val pos = BlockPos.MutableBlockPos(x, 0, z)
        var hash = 1
        for (y in 140 downTo 12) {
            val block = level.getBlockState(pos.setY(y)).block
            hash =
                hash * 31 +
                    tokens.getOrPut(block) {
                        SoloRoomMap.coreToken(BuiltInRegistries.BLOCK.getKey(block).toString())
                    }
        }
        rooms[tile] = definitions[hash]
    }

    companion object {
        private val definitions by lazy {
            Json.decodeFromString<List<SoloRoomDefinition>>(
                    requireNotNull(
                            RoomDetector::class
                                .java
                                .getResourceAsStream("/assets/mithrilpf/data/solo-rooms.json")
                        )
                        .bufferedReader()
                        .use { it.readText() }
                )
                .flatMap { room -> room.cores.map { it to room } }
                .toMap()
        }

        fun prepare() {
            definitions.size
        }
    }
}
