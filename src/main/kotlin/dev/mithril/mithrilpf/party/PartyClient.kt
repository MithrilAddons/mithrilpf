package dev.mithril.mithrilpf.party

import dev.mithril.mithrilpf.account.DeviceSessionStore
import dev.mithril.mithrilpf.account.LinkReceiptStore
import dev.mithril.mithrilpf.account.LinkTransport
import dev.mithril.mithrilpf.account.ServiceFailure
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

/**
 * Client owns gameplay/scheduling. One bounded worker owns disk, HTTP and the scoped credential.
 */
internal interface PartyHost {
    val uuid: String
    val name: String
    val connection: Any?
    val hasPlayer: Boolean
    val server: String?

    fun proof(): (String) -> Unit

    fun execute(action: () -> Unit)

    fun command(command: String)

    /**
     * Asks Hypixel's Mod API for the party; false when it cannot be sent yet. Not a chat command.
     */
    fun requestPartyInfo(): Boolean

    fun message(key: String)

    fun chatMessage(message: PartyChatMessage)

    fun chatStatus(key: String, retryText: String?)
}

class PartyClient
internal constructor(
    private val host: PartyHost,
    private val store: LinkReceiptStore,
    private val devices: DeviceSessionStore,
    private val flow: PartyFlow,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    constructor(
        client: Minecraft
    ) : this(
        MinecraftPartyHost(client),
        LinkReceiptStore(FabricLoader.getInstance().configDir.resolve("mithrilpf/link.json")),
        DeviceSessionStore(FabricLoader.getInstance().configDir.resolve("mithrilpf/device.json")),
        PartyFlow(LinkTransport::partyPost),
    )

    private val worker =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1)) { task ->
            Thread(task, "MithrilPF parties").apply { isDaemon = true }
        }
    private var renewChatAuth = false
    private val relay =
        PartyChatRelay(
            LinkTransport::chatPost,
            { task ->
                host.execute {
                    if (host.uuid == account) task()
                }
            },
            host::chatMessage,
            host::chatStatus,
            {
                renewChatAuth = true
                nextPoll = 0
            },
        )
    private val game = GamePartyTracker()
    private val invites = ArrayDeque<String>()
    private var pending: Future<*>? = null
    private var account = ""
    private var connection: Any? = null
    private var nativeToken: String? = null
    private var signingOut = false
    private var generation = 0
    private var busy = false
    private var nextPoll = 0L
    private var nextCommand = 0L
    private var nextInvite = 0L
    private var report: PartyReport? = null
    private var roundPending = false
    private var infoSent: Long? = null
    private var nextInfo = 0L
    private var failures = 0
    var party: PartyHandoff? = null
        private set

    var activity: FinderActivity? = null
        private set

    var status = "waiting"
        private set

    private fun now() = clock()

    private fun online(): Boolean {
        val server = host.server?.substringBefore(':')?.lowercase()?.trimEnd('.') ?: return false
        return host.hasPlayer &&
            host.connection != null &&
            (server == "hypixel.net" || server.endsWith(".hypixel.net"))
    }

    /**
     * Hypixel commands only follow a user action: this click, command or button reports the tracked
     * game party, and the server's reply may add one /p for missing players. An unknown game party
     * (after a launch or reconnect) is first read through Hypixel's Mod API, never a chat command.
     */
    fun invite() {
        val time = now()
        if (!online() || party?.ready != true || invites.isNotEmpty()) {
            message("not_ready")
            return
        }
        if (time < nextInvite) {
            message("cooldown")
            return
        }
        startRound(time)
    }

    /** Any click in the open chat screen invites while a ready party is still missing players. */
    fun chatClicked() {
        val time = now()
        if (
            online() &&
                party?.ready == true &&
                invites.isEmpty() &&
                (game.roster?.names?.size ?: 0) < 5 &&
                time >= nextInvite
        )
            startRound(time)
    }

    private fun startRound(time: Long) {
        nextInvite = time + 10_000
        message("checking")
        // Players only known by UUID may have left since; ask Hypixel again rather than guess.
        if (game.roster?.names?.any { it.startsWith(UNRESOLVED) } == true) game.reset()
        if (game.roster != null) return reportRoster(true)
        roundPending = true
        if (infoSent == null && time >= nextInfo) requestInfo(time)
    }

    fun chat(text: String) {
        if (online() && game.receive(text, host.name)) reportRoster(false)
    }

    /** Hypixel's Mod API answer, including answers to other mods' requests. Empty: no party. */
    fun partyInfo(leader: String?, members: Set<String>) {
        infoSent = null
        if (!online()) return
        val known = party?.members.orEmpty()
        fun name(uuid: String) =
            if (uuid == host.uuid) host.name
            else known.firstOrNull { it.uuid == uuid }?.name ?: "$UNRESOLVED$uuid"
        game.set(
            if (members.isEmpty()) GameRoster(host.name, listOf(host.name))
            else GameRoster(leader?.let(::name) ?: UNRESOLVED, members.map(::name))
        )
        val round = roundPending
        roundPending = false
        reportRoster(round)
    }

    fun partyInfoFailed() {
        infoSent = null
        if (roundPending) message("party_unknown")
        roundPending = false
    }

    private fun requestInfo(time: Long) {
        if (host.requestPartyInfo()) {
            infoSent = time
            nextInfo = time + 60_000 // Matches common Mod API use; Hypixel rate limits requests.
        } else nextInfo = time + 5_000
    }

    /** A user action ([round]) may invite; other roster changes only report who has joined. */
    private fun reportRoster(round: Boolean) {
        val current = party ?: return
        if (!current.youLead || !current.full) return
        val roster = game.roster ?: return
        if (!current.accepts(roster)) {
            invites.clear()
            report = null
            if (status != "conflict") message("conflict")
            status = "conflict"
            return
        }
        val pending = report?.takeIf { it.party.generation == current.generation }?.retry
        val action = if (round && current.ready) current.invited else pending
        report = PartyReport(current, roster, action)
        nextPoll = 0
    }

    fun sendChat(text: String) = relay.send(text)

    fun tick(deviceToken: String? = null, deviceSigningOut: Boolean = false) {
        val uuid = host.uuid
        val name = host.name
        val activeConnection = host.connection
        if (
            uuid != account ||
                connection !== activeConnection ||
                nativeToken != deviceToken ||
                signingOut != deviceSigningOut
        ) {
            resetConnection()
            account = uuid
            connection = activeConnection
            nativeToken = deviceToken
            signingOut = deviceSigningOut
        }
        if (signingOut) return
        val time = now()
        relay.tick(host.hasPlayer && activeConnection != null)
        val inGame = online()
        tickCommands(inGame, time)
        if (busy || time < nextPoll) return
        val attempt = generation
        val outgoing = report
        val renew = renewChatAuth
        renewChatAuth = false
        report = null // Never replay an uncertain invite request automatically.
        val prove = host.proof()
        busy = true
        nextPoll = time + 30_000
        worker.purge()
        pending = worker.submit {
            val (result, received, receivedChat) =
                exchange(uuid, name, inGame, outgoing, renew, prove)
            host.execute {
                if (generation == attempt) {
                    acceptReply(result, received, receivedChat, outgoing)
                }
            }
        }
    }

    private data class Exchange(val status: String, val reply: PartyReply?, val chat: ChatAccess?)

    private fun exchange(
        uuid: String,
        name: String,
        inGame: Boolean,
        outgoing: PartyReport?,
        renew: Boolean,
        prove: (String) -> Unit,
    ): Exchange {
        var reply: PartyReply? = null
        var chatAccess: ChatAccess? = null
        val result =
            try {
                if (renew) flow.clear()
                val receipt = devices.load(uuid)?.receipt ?: store.load(uuid)?.token
                if (receipt == null) {
                    flow.clear()
                    "unlinked"
                } else {
                    reply = flow.exchange(uuid, name, receipt, inGame, outgoing, prove)
                    chatAccess = flow.chatAccess()
                    "connected"
                }
            } catch (failure: Exception) {
                // Do not log tokens, receipts, access tokens, or HTTP exception details.
                if (failure is ServiceFailure && failure.statusCode == 401) "unlinked"
                else "unavailable"
            }
        return Exchange(result, reply, chatAccess)
    }

    private fun resetConnection() {
        relay.update(null)
        relay.tick(false)
        generation++
        pending?.cancel(true)
        busy = false
        failures = 0
        party = null
        activity = null
        report = null
        roundPending = false
        infoSent = null
        nextInfo = 0
        game.reset()
        invites.clear()
        nextPoll = 0
    }

    private fun tickCommands(inGame: Boolean, time: Long) {
        val current = party
        if (!inGame) {
            invites.clear()
            report = null
            roundPending = false
        }
        infoSent?.let { if (time - it > 10_000) partyInfoFailed() }
        if (
            inGame &&
                current?.youLead == true &&
                current.full &&
                game.roster == null &&
                infoSent == null &&
                time >= nextInfo
        )
            requestInfo(time)
        // Invites only exist in reply to a user's own invite request (see updateParty).
        if (inGame && time >= nextCommand && invites.isNotEmpty()) {
            if (current?.youLead == true && current.invited) {
                host.command("p ${invites.joinToString(" ")}")
                invites.clear()
                nextCommand = time + 1_000
            } else invites.clear()
        }
    }

    private fun acceptReply(
        result: String,
        received: PartyReply?,
        receivedChat: ChatAccess?,
        outgoing: PartyReport?,
    ) {
        busy = false
        status = result
        if (result != "unavailable" && !renewChatAuth) relay.update(receivedChat)
        updateParty(received, outgoing)
        failures = if (result == "unavailable") (failures + 1).coerceAtMost(4) else 0
        val delay = if (failures > 0) minOf(300, 30 shl failures) else received?.interval ?: 30
        nextPoll = if (report != null || renewChatAuth) 0 else now() + delay * 1_000L
    }

    private fun updateParty(received: PartyReply?, outgoing: PartyReport?) {
        val previous = party
        party = received?.party
        activity = received?.activity
        if (party != null && previous?.id != party?.id) message("reserved")
        val handoff = previous?.generation != party?.generation
        if (handoff) {
            invites.clear()
            roundPending = false
        }
        if (party?.full == true && (previous?.full != true || handoff)) message("full")
        if (party?.ready == true && (previous?.ready != true || handoff)) message("ready")
        if (received?.invites?.isNotEmpty() == true && outgoing?.retry != null && online()) {
            invites.addAll(received.invites)
            message("inviting")
        }
        if (
            outgoing?.roster?.names?.size == 5 &&
                received != null &&
                previous != null &&
                party == null
        )
            message("complete")
    }

    private fun message(key: String) {
        if (nativeToken != null && key in setOf("reserved", "full", "complete")) return
        host.message(key)
    }

    override fun close() {
        relay.close()
        generation++
        pending?.cancel(true)
        worker.shutdownNow()
        invites.clear()
    }

    private companion object {
        /** Marks a game party member Hypixel named only by UUID; never a valid player name. */
        const val UNRESOLVED = "#"
    }
}
