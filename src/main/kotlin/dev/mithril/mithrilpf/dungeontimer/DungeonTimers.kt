package dev.mithril.mithrilpf.dungeontimer

import dev.mithril.mithrilpf.soloclear.DungeonScore
import dev.mithril.mithrilpf.soloclear.SoloClearState
import dev.mithril.mithrilpf.soloroom.RoomDetector
import dev.mithril.mithrilpf.soloroom.SoloRoomResult
import dev.mithril.mithrilpf.soloroom.SoloRoomState
import dev.mithril.mithrilpf.sync.RecordSnapshot
import dev.mithril.mithrilpf.ui.Palette
import java.util.UUID
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.*
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.phys.AABB
import net.minecraft.world.scores.DisplaySlot
import org.slf4j.LoggerFactory

/**
 * All live state is client-thread owned. Snapshot textual signals before other mods rewrite them;
 * the passive packet observer never cancels packets or changes vanilla handling.
 */
object DungeonTimers {
    private val log = LoggerFactory.getLogger("MithrilPF tracking")
    private val definitions = DungeonTimerState.loadDefinitions()
    private val clock = HypixelTickCounter()
    private val location = DungeonLocation()
    private lateinit var storage: TrackingStorage
    private var world: Any? = null
    private var scanTicks = 0
    private var capture: DungeonRunCapture? = null
    private var started = false
    private var stopped = false
    private var ghost = false
    private var errorShown = false
    private var rooms: SoloRoomState? = null
    private var solo: SoloClearState? = null
    private var detector = RoomDetector()
    private var score = DungeonScore()
    private val tab = linkedMapOf<UUID, String>()
    var settings = TrackingSettings()
        private set

    val ready
        get() = ::storage.isInitialized && storage.ready && !storage.error

    fun syncSnapshot(player: String): RecordSnapshot =
        if (ready) RecordSnapshot.from(storage.records, player) else RecordSnapshot(emptyList())

    var state: DungeonTimerState? = null
        private set

    var ticks = 0L
        private set

    val floor
        get() = location.floor

    val inDungeon
        get() = location.inDungeon

    private val bounds =
        listOf(
            AABB(-72.0, 55.0, -40.0, -14.0, 146.0, 49.0),
            AABB(-40.0, 54.0, -40.0, 24.0, 99.0, 59.0),
            AABB(-40.0, 64.0, -40.0, 42.0, 118.0, 37.0),
            AABB(-40.0, 53.0, -40.0, 50.0, 112.0, 47.0),
            AABB(-40.0, 53.0, -8.0, 50.0, 112.0, 118.0),
            AABB(-40.0, 51.0, -8.0, 22.0, 110.0, 134.0),
            AABB(-8.0, 0.0, -8.0, 134.0, 254.0, 147.0),
        )

    fun register() {
        val client = Minecraft.getInstance()
        RoomDetector.prepare()
        storage =
            TrackingStorage(FabricLoader.getInstance().configDir.resolve("mithrilpf")) {
                client.execute(it)
            }
        storage.load { settings = it }
        ClientTickEvents.END_CLIENT_TICK.register { tick(it) }
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { _, _ -> reset() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
        ClientLifecycleEvents.CLIENT_STOPPING.register { storage.close() }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("mithrilpfpbs").executes { ctx ->
                    val player = client.player?.uuid?.toString()
                    ctx.source.sendFeedback(message("records"))
                    storage.records.forEach { (kind, players) ->
                        players[player].orEmpty().toSortedMap().forEach { (floor, splits) ->
                            splits.forEach { (name, best) ->
                                ctx.source.sendFeedback(
                                    Component.literal(
                                        "$kind · $floor · ${splitName(name)}: ${DungeonTimeFormat.pair(SplitTime(best.realMillis, best.ticks))}"
                                    )
                                )
                            }
                        }
                    }
                    1
                }
            )
            dispatcher.register(
                literal("mithrilpfstatus").executes { ctx ->
                    ctx.source.sendFeedback(
                        message(
                            "status",
                            floor ?: "—",
                            clock.packets,
                            ticks,
                            solo?.status ?: "waiting",
                            solo?.score?.toString() ?: "—",
                            if (ready) "ready" else "unavailable",
                        )
                    )
                    1
                }
            )
        }
        DungeonTimerHud.register()
    }

    fun update(value: TrackingSettings) {
        if (!ready) return
        if (settings.enabled && !value.enabled && started) invalidate()
        if (started && settings.paul != value.paul) solo?.invalidate("settings_changed")
        if (started && settings.rooms && !value.rooms) rooms?.invalidate()
        if (started && settings.solo && !value.solo) solo?.invalidate("disabled")
        settings = value
        storage.settings(value)
    }

    fun now() = TimerStamp(ticks, System.nanoTime())

    private fun reset() {
        val client = Minecraft.getInstance()
        world = client.level
        location.reset()
        clock.reset()
        ticks = 0
        scanTicks = 0
        state = null
        capture = null
        started = false
        stopped = false
        ghost = false
        tab.clear()
        score = DungeonScore()
        detector = RoomDetector()
        rooms = client.player?.gameProfile?.name?.let(::SoloRoomState)
        solo = client.player?.gameProfile?.name?.let(::SoloClearState)
    }

    private fun invalidate() {
        stopped = true
        capture = null
        state = null
        rooms?.invalidate()
        solo?.invalidate("disabled")
    }

    private fun onHypixel(client: Minecraft) =
        client.connection != null &&
            DungeonLocation.isHypixel(
                client.connection?.serverBrand(),
                client.currentServer?.ip,
                false,
            )

    private fun detect(client: Minecraft) {
        val board = client.level?.scoreboard ?: return
        val lines =
            board.playerTeams
                .map { it.playerPrefix.string + it.playerSuffix.string }
                .toMutableList()
        board.getDisplayObjective(DisplaySlot.SIDEBAR)?.let { objective ->
            lines.addAll(board.listPlayerScores(objective).map { it.ownerName().string })
        }
        observeLines(lines)
    }

    private fun observeLines(lines: List<String>) {
        val cleaned = lines.map(DungeonTimerState::clean)
        val previous = floor
        location.observe(cleaned, "raw scoreboard")
        if (started && previous != floor) invalidate()
        score.sidebar(cleaned)
    }

    private fun tick(client: Minecraft) {
        if (world !== client.level) reset()
        if (storage.error && client.player != null && !errorShown) {
            errorShown = true
            client.player?.sendSystemMessage(message("storage_error"))
        }
        if (client.level == null || !settings.enabled || !ready) return
        if (!onHypixel(client)) {
            ticks++
            return
        }
        if (++scanTicks % 10 == 0) detect(client)
        if (!inDungeon || stopped) return
        val player = client.player ?: return
        if (rooms == null) rooms = SoloRoomState(player.gameProfile.name)
        if (solo == null) solo = SoloClearState(player.gameProfile.name)
        val stamp = now()
        val box = floor?.lastOrNull()?.digitToIntOrNull()?.let { bounds.getOrNull(it - 1) }
        if (box?.contains(player.position()) == true)
            state?.let { record("splits", it.enterBoss(stamp)) }
        if (settings.rooms && !ghost) {
            try {
                floor?.let {
                    detector.tick(client, rooms!!, it, stamp) { name, times ->
                        roomResult(name, times)
                    }
                }
            } catch (e: Exception) {
                rooms?.invalidate()
                log.warn("Room tracking stopped for this run", e)
            }
        } else rooms?.leave(stamp)
        if (settings.solo && solo?.active == true) {
            val timer = state ?: return
            val elapsed = timer.rows(stamp)["Total"] ?: return
            val estimate =
                score.estimate(
                    elapsed.realMillis / 1000,
                    "Boss Entry" in timer.completed,
                    settings.paul,
                )
            solo
                ?.observe(
                    estimate,
                    ghost || player.isSpectator || player.isDeadOrDying || (score.deaths ?: 0) > 0,
                    stamp,
                )
                ?.let { time ->
                    record("solo", mapOf("300 Score" to time))
                }
        }
    }

    @JvmStatic
    fun onPacket(connection: Connection, packet: Packet<*>) {
        if (!::storage.isInitialized) return
        visitDungeonPackets(packet) { child, bundled ->
            if (
                child !is ClientboundPingPacket &&
                    child !is ClientboundSystemChatPacket &&
                    child !is ClientboundSetPlayerTeamPacket &&
                    child !is ClientboundPlayerInfoUpdatePacket &&
                    child !is ClientboundPlayerInfoRemovePacket &&
                    child !is ClientboundMapItemDataPacket &&
                    child !is ClientboundEntityEventPacket
            )
                return@visitDungeonPackets
            val received = System.nanoTime()
            val chat =
                (child as? ClientboundSystemChatPacket)
                    ?.takeUnless { it.overlay() }
                    ?.content()
                    ?.string
                    ?.let(DungeonTimerState::clean)
            val team =
                (child as? ClientboundSetPlayerTeamPacket)?.parameters?.orElse(null)?.let {
                    it.playerPrefix.string + it.playerSuffix.string
                }
            val entries =
                (child as? ClientboundPlayerInfoUpdatePacket)
                    ?.takeIf {
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME in
                            it.actions() ||
                            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER in it.actions()
                    }
                    ?.entries()
                    ?.map {
                        it.profileId() to it.displayName()?.string?.let(DungeonTimerState::clean)
                    }
            val client = Minecraft.getInstance()
            client.execute {
                if (client.connection?.connection !== connection || !onHypixel(client))
                    return@execute
                if (world !== client.level) reset()
                if (!settings.enabled || !ready) return@execute
                if (child is ClientboundPingPacket && clock.accept(child.id, bundled)) ticks++
                if (stopped) return@execute
                team?.let { observeLines(listOf(it)) }
                if (child is ClientboundPlayerInfoRemovePacket)
                    child.profileIds().forEach(tab::remove)
                if (entries != null) {
                    entries.forEach { (id, text) ->
                        if (text == null) tab.remove(id) else tab[id] = text
                    }
                    val participants = tab.values.mapNotNull(SoloRoomState::participant)
                    val name = client.player?.gameProfile?.name
                    if (rooms == null && name != null) rooms = SoloRoomState(name)
                    if (solo == null && name != null) solo = SoloClearState(name)
                    rooms?.roster(participants)
                    solo?.roster(participants)
                    if (
                        tab.values.any {
                            SoloRoomState.participant(it) == name && it.endsWith("(DEAD)")
                        }
                    )
                        ghost = true
                    score.tab(tab.values)
                }
                if (child is ClientboundMapItemDataPacket && inDungeon)
                    detector.mapId = child.mapId()
                if (
                    child is ClientboundEntityEventPacket &&
                        child.eventId.toInt() == 3 &&
                        inDungeon &&
                        state?.completed?.containsKey("Boss Entry") != true &&
                        (client.level?.let { child.getEntity(it) } as? Zombie)?.isBaby == true
                )
                    score.mimic = true
                chat?.let { chat(client, it, TimerStamp(ticks, received)) }
            }
        }
    }

    private fun chat(client: Minecraft, text: String, stamp: TimerStamp) {
        detect(client)
        val floor = floor ?: return
        if (!inDungeon) return
        val player = client.player ?: return
        val splits = definitions[floor] ?: definitions[floor.replace('M', 'F')].orEmpty()
        if (text == DungeonTimerState.START && !started) {
            started = true
            // Use raw incoming tab entries; fallback only fills missing initial data.
            if (tab.isEmpty())
                client.connection?.onlinePlayers.orEmpty().forEach {
                    it.tabListDisplayName?.string?.let(DungeonTimerState::clean)?.let { line ->
                        tab[it.profile.id] = line
                    }
                }
            val participants = tab.values.mapNotNull(SoloRoomState::participant).toSet()
            score.start()
            score.tab(tab.values)
            if (rooms == null) rooms = SoloRoomState(player.gameProfile.name)
            if (solo == null) solo = SoloClearState(player.gameProfile.name)
            rooms?.roster(participants)
            solo?.roster(participants)
            if (settings.rooms) rooms?.start()
            if (settings.solo) solo?.begin(floor, stamp)
            state = DungeonTimerState(floor, splits)
            val required =
                setOf("Blood Open", "Watcher Clear", "Total") +
                    if (splits.isEmpty()) emptySet()
                    else
                        splits.map { DungeonTimerState.clean(it.name) }.toSet() +
                            setOf("Portal", "Boss Entry")
            capture =
                DungeonRunCapture(
                    player.uuid.toString(),
                    floor,
                    System.currentTimeMillis(),
                    participants,
                    player.gameProfile.name,
                    required,
                )
        }
        score.chat(text)
        state?.let { timer ->
            record("splits", timer.chat(text, stamp))
            capture?.finish(timer)?.let(storage::append)
            if (timer.ended) {
                rooms?.invalidate()
                solo?.invalidate("left")
            }
        }
        if (text.contains("EXTRA STATS")) {
            capture = null
            if (state?.ended != true) state = null
            rooms?.invalidate()
            solo?.invalidate("left")
        }
        if (text.startsWith("You became a ghost") || Regex("^\\s*☠ You .+").matches(text)) {
            ghost = true
            solo?.invalidate("death")
        }
    }

    private fun record(kind: String, times: Map<String, SplitTime>) {
        val client = Minecraft.getInstance()
        val player = client.player?.uuid?.toString() ?: return
        val floor = floor ?: return
        storage.record(kind, player, floor, times) { old, changed ->
            if (client.player?.uuid?.toString() != player) return@record
            changed.forEach { (name, time) ->
                client.player?.sendSystemMessage(resultMessage(name, time, old[name]))
            }
            if (kind == "solo") {
                val time = times.getValue("300 Score")
                if (changed.isEmpty())
                    client.player?.sendSystemMessage(
                        resultMessage("300 Score", time, old["300 Score"])
                    )
                if (old["300 Score"]?.ticks?.let { time.ticks < it } != false) {
                    client.gui.setTimes(5, 80, 10)
                    client.gui.setTitle(message("solo_pb").withStyle(ChatFormatting.GOLD))
                    client.gui.setSubtitle(Component.literal(DungeonTimeFormat.ticks(time.ticks)))
                }
            }
        }
    }

    private fun resultMessage(name: String, time: SplitTime, previous: DungeonBest?): Component {
        val pb = SplitPbResult.of(time, previous)
        val regular = SoloRoomResult.of(time.realMillis, previous?.realMillis)
        val line =
            message(
                    "split_pb",
                    splitName(name),
                    Component.literal(pb?.timeText ?: regular.timeText).withStyle {
                        it.withColor(if (pb != null) 0x55FF55 else regular.color)
                    },
                )
                .withStyle { it.withColor(Palette.ACCENT and 0xFFFFFF) }
        if (pb != null)
            line.append(
                message(if (pb.tickOnly) "tick_pb_suffix" else "pb_suffix")
                    .withStyle(ChatFormatting.GOLD)
            )
        return line
    }

    private fun roomResult(room: String, times: Map<String, SplitTime>) {
        val client = Minecraft.getInstance()
        val player = client.player?.uuid?.toString() ?: return
        val floor = floor ?: return
        storage.record("rooms", player, floor, times.mapKeys { "$room · ${it.key}" }) { old, _ ->
            if (client.player?.uuid?.toString() != player) return@record
            times.forEach { (split, time) ->
                val result = SoloRoomResult.of(time.realMillis, old["$room · $split"]?.realMillis)
                val line =
                    message(
                            "room_result",
                            room,
                            message(if (split == "Cleared") "clear" else "secrets"),
                            Component.literal(result.timeText).withStyle {
                                it.withColor(result.color)
                            },
                        )
                        .withStyle { it.withColor(Palette.ACCENT and 0xFFFFFF) }
                if (result.newPb) line.append(message("pb_suffix").withStyle(ChatFormatting.GOLD))
                client.player?.sendSystemMessage(line)
            }
        }
    }

    fun bestTicks(floor: String, split: String): Long? =
        storage.records["splits"]
            ?.get(Minecraft.getInstance().player?.uuid?.toString())
            ?.get(floor)
            ?.get(split)
            ?.ticks

    fun estimate(timer: DungeonTimerState, stamp: TimerStamp): SplitTime? =
        DungeonRunStatistics.forEstimate(
                timer.floor,
                storage.statistics[Minecraft.getInstance().player?.uuid?.toString()]?.get(
                    timer.floor
                ),
            )
            ?.estimate(timer, stamp)

    fun splitName(name: String): String =
        when (name) {
            "Blood Open" -> message("blood_rush").string
            "Watcher Clear" -> message("blood_camp").string
            "Boss Entry" -> message("clear").string
            "Dragons" -> message("wither_king").string
            else -> name
        }

    fun message(key: String, vararg values: Any) =
        Component.translatable("tracking.mithrilpf.$key", *values)
}
