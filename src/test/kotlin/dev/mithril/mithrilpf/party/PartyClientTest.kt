package dev.mithril.mithrilpf.party

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.mithril.mithrilpf.account.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PartyClientTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Host : PartyHost {
        override var uuid = "a".repeat(32)
        override val name = "Alpha"
        override var connection: Any? = Any()
        override var hasPlayer = true
        override var server: String? = "mc.hypixel.net:25565"
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val commands = mutableListOf<String>()
        val messages = mutableListOf<String>()
        var proofs = 0
        var infoRequests = 0
        var infoAvailable = true

        override fun proof(): (String) -> Unit = { proofs++ }

        override fun execute(action: () -> Unit) {
            callbacks.add(action)
        }

        override fun command(command: String) {
            commands += command
        }

        override fun requestPartyInfo(): Boolean {
            infoRequests++
            return infoAvailable
        }

        override fun message(key: String) {
            messages += key
        }

        override fun chatMessage(message: PartyChatMessage) =
            error("No chat stream in this fixture")

        override fun chatStatus(key: String, retryText: String?) {
            messages += key
        }

        fun apply() = assertNotNull(callbacks.poll(5, TimeUnit.SECONDS), "worker completion")()
    }

    private inner class Harness : AutoCloseable {
        val host = Host()
        var time = 100_000L
        var response =
            JsonParser.parseString(javaClass.getResource("/mod-party-v1.json")!!.readText())
                .asJsonObject
        var failure: Int? = null
        var missing = listOf("Beta", "Gamma", "Delta", "Epsilon")
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val store = LinkReceiptStore(temporary.root.toPath().resolve("link.json"))
        val devices = DeviceSessionStore(temporary.root.toPath().resolve("device.json"))
        val client =
            PartyClient(
                host,
                store,
                devices,
                PartyFlow({ path, body, _ ->
                    failure?.let { throw ServiceFailure(it) }
                    requests += path to body.deepCopy()
                    when (path) {
                        "party-challenge" -> proofFixture(body, "party", "t".repeat(43))
                        "party-verify" ->
                            """{"version":1,"party_token":"${"t".repeat(43)}","expires_in_seconds":2592000,"user":{"uuid":"${host.uuid}"}}"""
                        "invite" -> {
                            val party = response.get("party")
                            if (party.isJsonNull) response.toString()
                            else {
                                party.asJsonObject.addProperty("invited", true)
                                response
                                    .deepCopy()
                                    .apply {
                                        add("invite", JsonArray().apply { missing.forEach(::add) })
                                    }
                                    .toString()
                            }
                        }
                        else -> response.toString()
                    }
                }),
                { time },
            )

        init {
            store.save(host.uuid, LinkReceipt("r".repeat(43), true))
        }

        fun tick(token: String? = null) {
            client.tick(token)
        }

        fun poll(token: String? = null) {
            time += 400_000
            tick(token)
            host.apply()
        }

        /** Hypixel Mod API answers; names map to the fixture's UUIDs (Outsider is not listed). */
        fun info(leader: String?, vararg names: String) {
            val ids =
                mapOf(
                    "Alpha" to 'a',
                    "Beta" to 'b',
                    "Gamma" to 'c',
                    "Delta" to 'd',
                    "Epsilon" to 'e',
                )
            fun uuid(name: String) = (ids[name] ?: 'f').toString().repeat(32)
            client.partyInfo(leader?.let(::uuid), names.map(::uuid).toSet())
        }

        fun solo() = info(null)

        /** Reads the party through the Mod API, then one click invites everyone. */
        fun inviteRound() {
            poll()
            tick()
            solo()
            client.chatClicked()
            tick()
            host.apply()
            time += 1000
            tick()
        }

        override fun close() = client.close()
    }

    @Test
    fun chatClickInvitesAllFourAndRetriesOnlyMissingPlayers() {
        Harness().use { h ->
            h.poll()
            assertEquals("connected", h.client.status)
            assertEquals(listOf("reserved", "full", "ready"), h.host.messages)
            h.tick()
            assertEquals(1, h.host.infoRequests)
            assertTrue(h.host.commands.isEmpty())
            h.solo()
            h.tick()
            h.host.apply()
            assertEquals("roster", h.requests.last().first)
            assertTrue(h.host.commands.isEmpty())
            h.client.chatClicked()
            h.tick()
            h.host.apply()
            h.time += 1000
            h.tick()
            assertEquals(listOf("p Beta Gamma Delta Epsilon"), h.host.commands)
            h.tick()
            h.missing = listOf("Gamma", "Epsilon")
            h.client.chatClicked()
            h.client.invite()
            assertEquals("cooldown", h.host.messages.last())
            h.time += 10_000
            h.client.invite()
            assertEquals("checking", h.host.messages.last())
            h.tick()
            h.host.apply()
            h.time += 1000
            h.tick()
            assertEquals(listOf("p Beta Gamma Delta Epsilon", "p Gamma Epsilon"), h.host.commands)
            assertEquals(1, h.host.infoRequests)
            assertEquals(1, h.host.proofs)
            assertEquals(2, h.requests.count { it.first == "invite" })
        }
    }

    @Test
    fun ticksAndUnrequestedInvitesNeverSendCommands() {
        Harness().use { h ->
            h.client.chatClicked()
            assertTrue(h.host.messages.isEmpty())
            h.poll()
            repeat(20) {
                h.time += 10_000
                h.tick()
            }
            assertTrue(h.host.infoRequests in 2..5)
            h.response.getAsJsonObject("party").addProperty("invited", true)
            h.response.add("invite", JsonArray().apply { h.missing.forEach(::add) })
            h.poll()
            repeat(5) {
                h.time += 1000
                h.tick()
            }
            assertTrue(h.host.commands.isEmpty())
            assertTrue(h.requests.none { it.first == "invite" })
        }
    }

    @Test
    fun clickWhilePartyIsUnknownWaitsForTheModApiAndResolvesUuids() {
        Harness().use { h ->
            h.poll()
            h.tick()
            h.client.chatClicked()
            assertEquals(1, h.host.infoRequests)
            h.info("Alpha", "Alpha", "Beta", "Outsider")
            assertEquals("conflict", h.client.status)
            h.time += 60_000
            h.client.chatClicked()
            assertEquals(2, h.host.infoRequests)
            h.info("Alpha", "Alpha", "Beta")
            h.tick()
            h.host.apply()
            assertEquals("invite", h.requests.last().first)
            assertEquals(
                listOf("Alpha", "Beta"),
                h.requests.last().second.getAsJsonArray("members").map { it.asString },
            )
            h.time += 1000
            h.tick()
            assertEquals(listOf("p Beta Gamma Delta Epsilon"), h.host.commands)
        }
    }

    @Test
    fun unansweredOrRejectedModApiRequestsEndThePendingClick() {
        Harness().use { h ->
            h.poll()
            h.tick()
            h.client.chatClicked()
            h.time += 11_000
            h.tick()
            assertEquals("party_unknown", h.host.messages.last())
            h.time += 60_000
            h.client.chatClicked()
            h.client.partyInfoFailed()
            assertEquals("party_unknown", h.host.messages.last())
            h.solo() // A late answer only updates the party; the click already ended.
            h.tick()
            h.host.apply()
            assertTrue(h.requests.none { it.first == "invite" })
            h.host.infoAvailable = false
            h.host.connection = Any() // A reconnect forgets the game party.
            h.time += 60_000
            h.tick()
            h.host.apply()
            h.client.chatClicked()
            h.host.infoAvailable = true
            h.time += 5_000
            h.tick()
            h.solo()
            h.tick()
            h.host.apply()
            assertEquals("invite", h.requests.last().first)
            assertTrue(h.host.commands.none { !it.startsWith("p ") })
        }
    }

    @Test
    fun joinAndLeaveLinesTrackTheRosterUntilEveryoneJoined() {
        Harness().use { h ->
            h.inviteRound()
            for (line in
                listOf(
                    "§b[MVP§c+§b] Beta §ejoined the party.",
                    "Gamma joined the party.",
                    "[VIP] Mallory: Delta joined the party.",
                    "Party > [VIP] Gamma: Epsilon joined the party.",
                    "[VIP] Delta joined the party.",
                    "[VIP] Delta has left the party.",
                )) h.client.chat(line)
            h.tick()
            h.host.apply()
            val report = h.requests.last()
            assertEquals("roster", report.first)
            assertEquals(
                listOf("Alpha", "Beta", "Gamma"),
                report.second.getAsJsonArray("members").map { it.asString },
            )
            h.client.chat("[MVP+] Delta joined the party.")
            h.client.chat("[MVP++] Epsilon joined the party.")
            h.tick()
            h.host.apply()
            assertEquals("roster", h.requests.last().first)
            assertEquals(5, h.requests.last().second.getAsJsonArray("members").size())
            assertEquals(listOf("p Beta Gamma Delta Epsilon"), h.host.commands)
        }
    }

    @Test
    fun outsiderInTheGamePartyBlocksInvitesUntilTheyLeave() {
        Harness().use { h ->
            h.poll()
            h.tick()
            h.solo()
            h.client.chat("[VIP] Outsider joined the party.")
            assertEquals("conflict", h.client.status)
            h.client.chat("[VIP] Beta joined the party.")
            h.tick()
            h.host.apply()
            assertTrue(h.requests.none { it.first == "invite" || it.first == "roster" })
            h.time += 10_000
            h.client.chatClicked()
            assertEquals("conflict", h.client.status)
            h.client.chat("[VIP] Outsider has left the party.")
            h.time += 10_000
            h.client.chatClicked()
            h.tick()
            h.host.apply()
            assertEquals("invite", h.requests.last().first)
            assertEquals(
                listOf("Alpha", "Beta"),
                h.requests.last().second.getAsJsonArray("members").map { it.asString },
            )
            assertTrue(h.host.commands.isEmpty())
        }
    }

    @Test
    fun partyTrackedFromChatNeedsNoModApiAndMustBeLed() {
        Harness().use { h ->
            h.poll()
            h.client.chat("You have joined [MVP+] Beta's party!")
            h.client.chat("You'll be partying with: [VIP] Gamma, Delta")
            h.client.chatClicked()
            assertEquals("conflict", h.client.status)
            h.client.chat("The party was transferred to [MVP+] Alpha by [MVP+] Beta")
            h.time += 10_000
            h.client.chatClicked()
            h.tick()
            h.host.apply()
            assertEquals("invite", h.requests.last().first)
            assertEquals(
                setOf("Alpha", "Beta", "Gamma", "Delta"),
                h.requests.last().second.getAsJsonArray("members").map { it.asString }.toSet(),
            )
            h.time += 1000
            h.tick()
            assertEquals(0, h.host.infoRequests)
            assertEquals(listOf("p Beta Gamma Delta Epsilon"), h.host.commands)
        }
    }

    @Test
    fun signOutCancelsQueuedInviteAndIgnoresHeldReply() {
        Harness().use { h ->
            h.poll("native")
            assertFalse("reserved" in h.host.messages)
            h.tick("native")
            h.solo()
            h.client.chatClicked()
            h.tick("native")
            val pending = assertNotNull(h.host.callbacks.poll(5, TimeUnit.SECONDS))
            h.client.tick("native", true)
            pending()
            h.time += 1000
            h.client.tick("native", true)
            assertNull(h.client.party)
            assertTrue(h.host.commands.isEmpty())
        }
    }

    @Test
    fun accountOrConnectionSwitchDiscardsQueuedInviteAndPartyState() {
        Harness().use { h ->
            h.poll()
            h.tick()
            h.solo()
            h.client.chatClicked()
            h.tick()
            h.host.apply()
            h.host.connection = null
            h.host.hasPlayer = false
            h.time += 1000
            h.tick()
            h.host.apply()
            assertTrue(h.host.commands.isEmpty())
            h.host.uuid = "b".repeat(32)
            h.tick()
            h.host.apply()
            assertEquals("unlinked", h.client.status)
            assertNull(h.client.party)
        }
    }

    @Test
    fun outsiderFromTheModApiBlocksInvitesAndMembersNeverRunLeaderCommands() {
        Harness().use { h ->
            h.client.invite()
            assertEquals("not_ready", h.host.messages.last())
            h.poll()
            h.info("Alpha", "Alpha", "Outsider")
            assertEquals("conflict", h.client.status)
            h.client.chatClicked()
            assertTrue(h.requests.none { it.first == "invite" })
            h.response.getAsJsonObject("party").addProperty("you_lead", false)
            h.poll()
            h.tick()
            h.client.chatClicked()
            h.client.invite()
            assertTrue(h.host.commands.isEmpty())
            assertEquals("not_ready", h.host.messages.last())
        }
    }

    @Test
    fun completedRosterClearsPartyAndReportsCompletion() {
        Harness().use { h ->
            h.poll()
            h.info("Alpha", "Alpha", "Beta", "Gamma", "Delta", "Epsilon")
            h.response.add("party", com.google.gson.JsonNull.INSTANCE)
            h.tick()
            h.host.apply()
            assertNull(h.client.party)
            assertEquals("complete", h.host.messages.last())
        }
    }

    @Test
    fun nativeReceiptTakesPriorityAndUnavailableServiceBacksOff() {
        Harness().use { h ->
            h.devices.save(
                h.host.uuid,
                DeviceSession(
                    "d".repeat(43),
                    "n".repeat(43),
                    System.currentTimeMillis() / 1000 + 1000,
                ),
            )
            h.poll("native")
            assertEquals(
                "n".repeat(43),
                h.requests
                    .first { it.first == "party-challenge" }
                    .second
                    .get("receipt_token")
                    .asString,
            )
            h.failure = 503
            h.poll("native")
            assertEquals("unavailable", h.client.status)
            val calls = h.requests.size
            h.tick("native")
            assertEquals(calls, h.requests.size)
            h.failure = 401
            h.poll("native")
            assertEquals("unlinked", h.client.status)
        }
    }

    @Test
    fun foreignServersCannotSendInvitesAndGenerationChangesDropPendingClicks() {
        Harness().use { h ->
            h.poll()
            h.tick()
            h.client.chatClicked()
            h.response.getAsJsonObject("party").addProperty("handoff_id", "batch0000002")
            h.poll()
            h.solo() // The click belonged to the previous generation.
            h.tick()
            h.host.apply()
            assertTrue(h.requests.none { it.first == "invite" })
            for (server in listOf(null, "hypixel.net.evil.invalid", "localhost")) {
                h.host.server = server
                h.host.commands.clear()
                h.tick()
                h.client.invite()
                h.client.chatClicked()
                assertTrue(h.host.commands.isEmpty())
                assertEquals("not_ready", h.host.messages.last())
            }
        }
    }
}
