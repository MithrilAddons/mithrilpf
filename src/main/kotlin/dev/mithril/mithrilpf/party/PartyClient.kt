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
    private val parser = PartyRosterParser()
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
    private var requested = false
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
     * game party, and the server's reply may add one /p for missing players. Only when the game
     * party is unknown (after a launch or reconnect) does it first send one /party list.
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
        val current = party ?: return
        nextInvite = time + 10_000
        message("checking")
        val known = game.roster
        if (known != null) {
            if (accepted(current, known)) report = PartyReport(current, known, current.invited)
            nextPoll = 0
            return
        }
        requested = true
        parser.request(time)
        host.command("party list")
        nextCommand = time + 1_000
    }

    fun chat(text: String) {
        if (!online()) return
        val listed = parser.receive(text, host.name, now())
        if (listed != null) game.set(listed) else if (!game.receive(text, host.name)) return
        val current = party ?: return
        if (!current.youLead || !current.full) return
        val updated = game.roster ?: return
        if (!accepted(current, updated)) return
        val action = if (listed != null && requested && current.ready) current.invited else null
        if (listed != null) requested = false
        report = PartyReport(current, updated, action)
        nextPoll = 0
    }

    private fun accepted(current: PartyHandoff, roster: GameRoster): Boolean {
        if (current.accepts(roster)) return true
        invites.clear()
        report = null
        requested = false
        if (status != "conflict") message("conflict")
        status = "conflict"
        return false
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
        requested = false
        game.reset()
        invites.clear()
        parser.reset()
        nextPoll = 0
    }

    private fun tickCommands(inGame: Boolean, time: Long) {
        val current = party
        if (!inGame) {
            invites.clear()
            parser.reset()
            report = null
            requested = false
        }
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
            parser.reset()
            requested = false
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
}
