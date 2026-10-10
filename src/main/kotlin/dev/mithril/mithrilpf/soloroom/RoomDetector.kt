package dev.mithril.mithrilpf.soloroom

import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import java.util.IdentityHashMap
import kotlinx.serialization.json.Json
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData

/** Client-thread only. Maximum two loaded 129-block columns per tick; no network lookup. */
class RoomDetector {
    private val runMap = RunMapCapture()
    val replay = RunReplay()
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
        captureSolo: Boolean = false,
        result: (List<RoomResult>) -> Unit,
    ) {
        val player = client.player ?: return
        if (captureSolo) runMap.times.observe(now, player.x, player.z)
        val tile = SoloRoomMap.tile(player.x, player.z)
        if (
            !RunMapCapture.canTrack(
                state.eligible,
                captureSolo,
                tile,
                player.isDeadOrDying,
                player.isSpectator,
            )
        ) {
            state.leave(now)
            return
        }
        requireNotNull(tile)
        ticks++
        if (captureSolo) runMap.visit(tile)
        identify(client, tile)
        identify(client, scan++ % 36)
        val id = player.inventory.getItem(8).get(DataComponents.MAP_ID) ?: mapId
        val data = id?.let { client.level?.getMapData(it) }
        val colors = data?.colors
        if (colors != null && calibration == null && ticks >= nextCalibration) {
            nextCalibration = ticks + 20
            calibration = SoloRoomMap.calibrate(colors, floor)
        }
        val map = calibration
        if (!state.eligible) return
        val color = { index: Int ->
            if (map != null && colors != null) map.color(colors, index) else 0
        }
        val others = teammateTiles(client, state.teammates, map, data)
        val results = state.tick(rooms.asList(), tile, color, others, now)
        if (results.isNotEmpty()) result(results)
    }

    /** Where living teammates stand: loaded players, plus their markers on the dungeon map. */
    private fun teammateTiles(
        client: Minecraft,
        teammates: Set<String>,
        map: SoloRoomMap?,
        data: MapItemSavedData?,
    ): Set<Int> {
        val player = client.player
        if (teammates.isEmpty() || player == null) return emptySet()
        val loaded =
            client.level?.players().orEmpty().filter { other ->
                other !== player && teammates.any { it.equals(other.gameProfile.name, true) }
            }
        val markers = data?.decorations?.map { it.x() to it.y() }.orEmpty()
        return loaded.mapNotNull { SoloRoomMap.tile(it.x, it.z) }.toSet() +
            map?.teammateTiles(markers, player.x, player.z).orEmpty()
    }

    /** The room secret counter, for room PBs in any run. */
    fun counter(client: Minecraft, state: SoloRoomState, text: String, now: TimerStamp) {
        val player = client.player ?: return
        SoloRoomMap.tile(player.x, player.z)?.let { state.counter(rooms[it], text, now) }
    }

    fun observeSecrets(client: Minecraft, text: String, now: TimerStamp) {
        val player = client.player ?: return
        val tile = SoloRoomMap.tile(player.x, player.z) ?: return
        runMap.observeSecrets(tile, text)?.let { (found, total) ->
            replay.observeRoomSecrets(now, tile, found, total)
        }
    }

    fun beginRun(client: Minecraft, now: TimerStamp) {
        val player = client.player ?: return
        runMap.times.begin(now, player.x, player.z)
        replay.begin(now, player.x, player.z, player.yRot)
    }

    fun replaySample(client: Minecraft, now: TimerStamp, collected: Int?, finish: Boolean = false) {
        val player = client.player ?: return
        replay.observe(now, player.x, player.z, player.yRot, collected, finish)
    }

    fun snapshot(
        client: Minecraft,
        now: TimerStamp,
        secretsFound: Int?,
        crypts: Int?,
    ): CapturedMap? {
        val id = client.player?.inventory?.getItem(8)?.get(DataComponents.MAP_ID) ?: mapId
        val colors = id?.let { client.level?.getMapData(it)?.colors }
        val map = runMap.snapshot(calibration, colors, rooms.toList(), now, secretsFound, crypts)
        val recorded = replay.freeze(map)
        return map?.let { CapturedMap(it, recorded) }
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
