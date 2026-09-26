package dev.mithril.mithrilpf.party

import dev.mithril.mithrilpf.account.LinkReceiptStore
import dev.mithril.mithrilpf.account.LinkTransport
import dev.mithril.mithrilpf.account.ServiceFailure
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Client owns gameplay/scheduling. One bounded worker owns disk, HTTP and the scoped credential.
 */
class PartyClient(private val client: Minecraft) : AutoCloseable {
    private val worker =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1)) { task ->
            Thread(task, "MithrilPF parties").apply { isDaemon = true }
        }
    private val store =
        LinkReceiptStore(FabricLoader.getInstance().configDir.resolve("mithrilpf/link.json"))
    private val flow = PartyFlow(LinkTransport::partyPost)
    private val parser = PartyRosterParser()
    private val invites = ArrayDeque<String>()
    private var pending: Future<*>? = null
    private var account = ""
    private var connection: Any? = null
    private var generation = 0
    private var busy = false
    private var nextPoll = 0L
    private var nextRoster = 0L
    private var nextCommand = 0L
    private var report: PartyReport? = null
    private var retry = false
    private var failures = 0
    var party: PartyHandoff? = null
        private set

    var status = "waiting"
        private set

    private fun now() = System.nanoTime() / 1_000_000

    private fun online(): Boolean {
        val host =
            client.currentServer?.ip?.substringBefore(':')?.lowercase()?.trimEnd('.')
                ?: return false
        return client.player != null &&
            client.connection != null &&
            (host == "hypixel.net" || host.endsWith(".hypixel.net"))
    }

    fun reinvite() {
        if (!online() || party?.ready != true || party?.invited != true || invites.isNotEmpty()) {
            message("not_ready")
            return
        }
        retry = true
        nextRoster = 0
        message("checking")
    }

    fun chat(text: String) {
        if (!online()) return
        val roster = parser.receive(text, client.user.name, now()) ?: return
        val current = party ?: return
        if (!current.youLead) return
        if (!current.accepts(roster)) {
            invites.clear()
            retry = false
            if (status != "conflict") message("conflict")
            status = "conflict"
            return
        }
        val action = if (current.ready && (!current.invited || retry)) retry else null
        report = PartyReport(current, roster, action)
        retry = false
        nextPoll = 0
    }

    fun tick() {
        val user = client.user
        val uuid = user.profileId.toString().replace("-", "")
        val activeConnection = client.connection
        if (uuid != account || connection !== activeConnection) {
            generation++
            pending?.cancel(true)
            account = uuid
            connection = activeConnection
            busy = false
            failures = 0
            party = null
            report = null
            retry = false
            invites.clear()
            parser.reset()
            nextPoll = 0
            nextRoster = 0
        }
        val time = now()
        val inGame = online()
        val current = party
        if (!inGame) {
            invites.clear()
            parser.reset()
            report = null
            retry = false
        }
        if (inGame && time >= nextCommand && invites.isNotEmpty()) {
            if (current?.youLead == true && current.invited) {
                client.connection?.sendCommand("party invite ${invites.removeFirst()}")
                nextCommand = time + 1_000
            } else invites.clear()
        }
        if (
            inGame &&
                current?.youLead == true &&
                current.full &&
                invites.isEmpty() &&
                time >= nextRoster &&
                time >= nextCommand &&
                (current.ready || current.invited)
        ) {
            parser.request(time)
            client.connection?.sendCommand("party list")
            nextRoster = time + 10_000
            nextCommand = time + 1_000
        }
        if (busy || time < nextPoll) return
        val attempt = generation
        val outgoing = report
        report = null // Never replay an uncertain invite request automatically.
        val service = client.services().sessionService()
        busy = true
        nextPoll = time + 30_000
        worker.purge()
        pending = worker.submit {
            var reply: PartyReply? = null
            val result =
                try {
                    val receipt = store.load(uuid)
                    if (receipt == null) {
                        flow.clear()
                        "unlinked"
                    } else {
                        reply =
                            flow.exchange(uuid, user.name, receipt.token, inGame, outgoing) {
                                serverId ->
                                service.joinServer(user.profileId, user.accessToken, serverId)
                            }
                        "connected"
                    }
                } catch (failure: Exception) {
                    // Do not log tokens, receipts, access tokens, or HTTP exception details.
                    if (failure is ServiceFailure && failure.statusCode == 401) "unlinked"
                    else "unavailable"
                }
            val received = reply
            client.execute {
                if (generation == attempt) {
                    busy = false
                    status = result
                    val previous = party
                    party = received?.party
                    if (party != null && previous?.id != party?.id) message("reserved")
                    if (previous?.generation != party?.generation) {
                        invites.clear()
                        parser.reset()
                        nextRoster = 0
                    }
                    if (
                        party?.full == true &&
                            (previous?.full != true || previous.generation != party?.generation)
                    )
                        message("full")
                    if (received?.invites?.isNotEmpty() == true && online()) {
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
                    failures = if (result == "unavailable") (failures + 1).coerceAtMost(4) else 0
                    val delay =
                        if (failures > 0) minOf(300, 30 shl failures) else received?.interval ?: 30
                    nextPoll = if (report != null) 0 else now() + delay * 1_000L
                }
            }
        }
    }

    private fun message(key: String) {
        client.player?.sendSystemMessage(Component.translatable("party.mithrilpf.$key"))
    }

    override fun close() {
        generation++
        pending?.cancel(true)
        worker.shutdownNow()
        invites.clear()
    }
}
