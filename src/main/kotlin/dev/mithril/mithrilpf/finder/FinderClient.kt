package dev.mithril.mithrilpf.finder

import com.google.gson.JsonObject
import dev.mithril.mithrilpf.account.LinkTransport
import dev.mithril.mithrilpf.account.NativeAccount
import dev.mithril.mithrilpf.account.ServiceFailure
import dev.mithril.mithrilpf.dungeontimer.DungeonTimers
import dev.mithril.mithrilpf.ui.Palette
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/** Separate bounded lanes keep a held state request from delaying a user's actions. */
class FinderClient(private val client: Minecraft, private val account: NativeAccount) :
    AutoCloseable {
    private val states = lane("state")
    private val reads = lane("listings")
    private val writes = lane("actions")
    private val notices = FinderNotices()
    private val presetStore =
        FinderPresetStore(FabricLoader.getInstance().configDir.resolve("mithrilpf/finder.json"))
    var presets: Map<String, FinderPreset> = emptyMap()
        private set

    var presetsLoaded = false
        private set

    var presetError = false
        private set

    private var stateTask: Future<*>? = null
    private var readTask: Future<*>? = null
    private var writeTask: Future<*>? = null
    private var identity = ""
    private var token: String? = null
    private var generation = 0
    private var mutation = 0
    private var stateBusy = false
    private var readBusy = false
    private var nextState = 0L
    private var nextRead = 0L
    var busy = false
        private set

    var state: FinderState? = null
        private set

    var listings: List<FinderParty> = emptyList()
        private set

    var detail: FinderParty? = null
        private set

    var floor = "M7"
        private set

    var selected: String? = null
        private set

    var error: String? = null
        private set

    var offline = false
        private set

    var revision = 0
        private set

    private fun lane(name: String) =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1)) { task ->
            Thread(task, "MithrilPF finder $name").apply { isDaemon = true }
        }

    fun tick(visible: Boolean) {
        val uuid = client.user.profileId.toString().replace("-", "")
        val session = account.session
        if (identity != uuid || token != session?.token) {
            reset()
            identity = uuid
            token = session?.token
        }
        val credential = token ?: return
        state?.let { current ->
            for (notice in notices.accept(current.notices)) {
                if (
                    notice.kind !in
                        setOf(
                            "placed",
                            "reserved",
                            "party_full",
                            "party_closed",
                            "left_party",
                            "leader_now",
                            "party_joined",
                            "stopped_looking",
                        )
                )
                    continue
                client.player?.sendSystemMessage(
                    Component.translatable("finder.mithrilpf.notice.${notice.kind}").withStyle {
                        it.withColor(Palette.ACCENT and 0xFFFFFF)
                            .withClickEvent(ClickEvent.RunCommand("/mpf"))
                    }
                )
                if (
                    client.player != null &&
                        DungeonTimers.settings.finderSound &&
                        notice.kind in setOf("placed", "party_full")
                )
                    client.soundManager.play(
                        SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.0f)
                    )
            }
        }
        val active =
            visible || client.player != null || state?.looking != null || state?.party != null
        if (!active || account.busy) return
        val now = System.nanoTime()
        if (!stateBusy && !busy && now >= nextState) poll(credential)
        if (visible && !readBusy && !busy && now >= nextRead) read(credential)
    }

    fun changeFloor(value: String) {
        require(value in setOf("F7", "M7"))
        if (floor == value) return
        floor = value
        selected = null
        listings = emptyList()
        detail = null
        nextRead = 0
        revision++
    }

    fun select(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{12}")))
        if (selected == id) return
        selected = id
        detail = null
        nextRead = 0
        revision++
    }

    fun refresh() {
        nextRead = 0
        nextState = 0
    }

    fun clearSelection() {
        selected = null
        detail = null
        nextRead = 0
        revision++
    }

    fun action(
        path: String,
        body: JsonObject,
        remember: FinderPreset? = null,
        done: (Boolean) -> Unit = {},
    ) {
        val credential = token ?: return
        if (busy || account.busy) return
        val attempt = generation
        val uuid = identity
        busy = true
        error = null
        mutation++
        revision++
        writes.purge()
        val request = body.deepCopy()
        val requestedFloor = body.get("floor")?.asString ?: state?.party?.floor ?: floor
        writeTask = writes.submit {
            val result = runCatching {
                val response =
                    LinkTransport.finderRequest("party/client/$path", request, credential)
                if (path == "chat/report") {
                    FinderProtocol.parse(response)
                    null
                } else FinderProtocol.state(response, uuid)
            }
            val saved =
                if (result.isSuccess && remember != null) {
                    runCatching { presetStore.save(uuid, requestedFloor, remember) }.isSuccess
                } else null
            client.execute {
                if (attempt == generation) {
                    busy = false
                    if (saved != null) {
                        presetError = !saved
                        if (saved) presets = presets + (requestedFloor to requireNotNull(remember))
                    }
                    result
                        .onSuccess { value ->
                            if (value != null) state = value
                            offline = false
                        }
                        .onFailure(::failed)
                    nextState = 0
                    nextRead = 0
                    revision++
                    done(result.isSuccess)
                }
            }
        }
    }

    private fun poll(credential: String) {
        val attempt = generation
        val change = mutation
        val uuid = identity
        val known = state
        val body =
            JsonObject().apply {
                addProperty("version", 1)
                if (known != null) {
                    addProperty("known", known.version)
                    addProperty("state_id", known.id)
                }
            }
        stateBusy = true
        states.purge()
        stateTask = states.submit {
            val result = runCatching {
                FinderProtocol.state(
                    LinkTransport.finderRequest("party/client/state", body, credential),
                    uuid,
                )
            }
            client.execute {
                if (attempt == generation) {
                    stateBusy = false
                    if (change == mutation && !busy) {
                        result
                            .onSuccess { value ->
                                if (value != null) state = value
                                offline = false
                            }
                            .onFailure(::failed)
                        nextState =
                            System.nanoTime() +
                                TimeUnit.SECONDS.toNanos(if (result.isSuccess) 1 else 10)
                        revision++
                    }
                }
            }
        }
    }

    private fun read(credential: String) {
        val attempt = generation
        val change = mutation
        val requestedFloor = floor
        val requestedParty = selected
        val loadPresets = !presetsLoaded
        val uuid = identity
        readBusy = true
        reads.purge()
        readTask = reads.submit {
            if (loadPresets) {
                val saved = runCatching { presetStore.load(uuid) }
                client.execute {
                    if (attempt == generation) {
                        presetsLoaded = true
                        presetError = saved.isFailure
                        presets = saved.getOrDefault(emptyMap())
                    }
                }
            }
            val result = runCatching {
                val rows =
                    FinderProtocol.listings(
                        LinkTransport.finderRequest(
                            "party/client/listings?floor=$requestedFloor",
                            null,
                            credential,
                        ),
                        requestedFloor,
                    )
                val details =
                    if (requestedParty != null && rows.any { it.id == requestedParty }) {
                        try {
                            FinderProtocol.detail(
                                LinkTransport.finderRequest(
                                    "party/client/listings/$requestedParty",
                                    null,
                                    credential,
                                ),
                                requestedParty,
                            )
                        } catch (failure: ServiceFailure) {
                            if (failure.statusCode == 404) null else throw failure
                        }
                    } else null
                rows to details
            }
            client.execute {
                if (attempt == generation) {
                    readBusy = false
                    if (
                        change == mutation && requestedFloor == floor && requestedParty == selected
                    ) {
                        result
                            .onSuccess { (rows, details) ->
                                listings = rows
                                detail = details
                            }
                            .onFailure(::failed)
                        nextRead =
                            System.nanoTime() +
                                TimeUnit.SECONDS.toNanos(if (result.isSuccess) 5 else 10)
                        revision++
                    }
                }
            }
        }
    }

    private fun failed(failure: Throwable) {
        val code = (failure as? ServiceFailure)?.statusCode
        error =
            (failure as? ServiceFailure)?.reason
                ?: when (code) {
                    401 -> "expired"
                    403 -> "restricted"
                    409 -> "changed"
                    422 -> "invalid"
                    429 -> "limited"
                    else -> "unavailable"
                }
        offline = code == null || code >= 500
        if (code == 401) account.load()
    }

    private fun reset() {
        generation++
        stateTask?.cancel(true)
        readTask?.cancel(true)
        writeTask?.cancel(true)
        stateBusy = false
        readBusy = false
        busy = false
        nextState = 0
        nextRead = 0
        state = null
        notices.reset()
        presets = emptyMap()
        presetsLoaded = false
        presetError = false
        listings = emptyList()
        detail = null
        selected = null
        floor = "M7"
        error = null
        offline = false
        revision++
    }

    override fun close() {
        reset()
        states.shutdownNow()
        reads.shutdownNow()
        writes.shutdownNow()
    }
}
