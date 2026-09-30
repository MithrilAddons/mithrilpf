package dev.mithril.mithrilpf.party

import com.google.gson.JsonObject
import dev.mithril.mithrilpf.account.ServiceFailure
import dev.mithril.mithrilpf.account.proofFixture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PartyFlowTest {
    private val uuid = "a".repeat(32)
    private val token = "t".repeat(43)
    private var proof = ""
    private val receipt = "r".repeat(43)
    private val state = checkNotNull(javaClass.getResource("/mod-party-v1.json")).readText()
    private val party
        get() = PartyProtocol.reply(PartyProtocol.parse(state)).party!!

    private fun auth(path: String, body: JsonObject) =
        when (path) {
            "party-challenge" ->
                proofFixture(body, "party", token).also {
                    proof = PartyProtocol.parse(it)["server_id"].asString
                }
            "party-verify" ->
                """{"version":1,"party_token":"$token","expires_in_seconds":2592000,"user":{"uuid":"$uuid"}}"""
            else -> state
        }

    @Test
    fun `credential is reused for 30 days and renewed by fresh ownership proof`() {
        var now = 0L
        var proofs = 0
        val calls = mutableListOf<String>()
        val flow =
            PartyFlow(
                { path, body, bearer ->
                    calls += path
                    assertEquals(if (path.startsWith("party-")) null else token, bearer)
                    auth(path, body)
                },
                { now },
            )
        repeat(2) {
            flow.exchange(uuid, "Alpha", receipt, true, null) {
                assertEquals(proof, it)
                proofs++
            }
        }
        assertEquals(1, proofs)
        now += TimeUnit.DAYS.toNanos(30)
        flow.exchange(uuid, "Alpha", receipt, false, null) { proofs++ }
        assertEquals(2, proofs)
        assertEquals(3, calls.count { it == "presence" })
    }

    @Test
    fun `stale handoffs offline clients and foreign rosters never send actions`() {
        val calls = mutableListOf<String>()
        val flow =
            PartyFlow({ path, body, _ ->
                calls += path
                auth(path, body)
            })
        val good = PartyReport(party, GameRoster("Alpha", listOf("Alpha")), false)
        val bad =
            listOf(
                good.copy(party = party.copy(generation = "other0000001")),
                good.copy(roster = GameRoster("Alpha", listOf("Alpha", "Outsider"))),
                good.copy(roster = GameRoster("Beta", listOf("Alpha", "Beta"))),
            )
        bad.forEach { flow.exchange(uuid, "Alpha", receipt, true, it) {} }
        flow.exchange(uuid, "Alpha", receipt, false, good) {}
        assertFalse("invite" in calls)
        flow.exchange(uuid, "Alpha", receipt, true, good) {}
        assertEquals(1, calls.count { it == "invite" })
    }

    @Test
    fun `failed invites are never automatically retried and logout requires new proof`() {
        var fail = false
        var invites = 0
        var proofs = 0
        val flow =
            PartyFlow({ path, body, _ ->
                if (fail) throw ServiceFailure(401)
                if (path == "invite") {
                    invites++
                    throw ServiceFailure(503)
                }
                auth(path, body)
            })
        assertFails {
            flow.exchange(
                uuid,
                "Alpha",
                receipt,
                true,
                PartyReport(party, GameRoster("Alpha", listOf("Alpha")), false),
            ) {
                proofs++
            }
        }
        flow.exchange(uuid, "Alpha", receipt, true, null) { proofs++ }
        assertEquals(1, invites)
        assertEquals(1, proofs)
        fail = true
        assertFails { flow.exchange(uuid, "Alpha", receipt, true, null) {} }
        fail = false
        flow.exchange(uuid, "Alpha", receipt, true, null) { proofs++ }
        assertEquals(2, proofs)
    }

    @Test
    fun `commands are restricted to expected validated missing player names`() {
        val response = PartyProtocol.parse(state)
        response.getAsJsonObject("party").addProperty("invited", true)
        response.add("invite", com.google.gson.JsonArray().apply { add("Beta") })
        assertEquals(listOf("Beta"), PartyProtocol.reply(response).invites)
        for (bad in listOf("Beta;party leave", "/kick Alpha", "Outsider", "Alpha")) {
            response.add("invite", com.google.gson.JsonArray().apply { add(bad) })
            assertFails { PartyProtocol.reply(response) }
        }
        assertFails { PartyProtocol.parse("x".repeat(16385)) }
        assertFails { PartyProtocol.reply(JsonObject()) }
    }

    @Test
    fun `cancellation after Mojang proof never verifies or sends a gameplay action`() {
        val calls = mutableListOf<String>()
        val flow =
            PartyFlow({ path, body, _ ->
                calls += path
                auth(path, body)
            })
        try {
            assertFails {
                flow.exchange(uuid, "Alpha", receipt, true, null) {
                    Thread.currentThread().interrupt()
                }
            }
            assertEquals(listOf("party-challenge"), calls)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `receipt replacement requires fresh authorization`() {
        var proofs = 0
        val flow = PartyFlow({ path, body, _ -> auth(path, body) })
        for (link in listOf(receipt, "s".repeat(43))) flow.exchange(
            uuid,
            "Alpha",
            link,
            true,
            null,
        ) {
            proofs++
        }
        assertEquals(2, proofs)
    }

    @Test
    fun `chat reuses party credential and survives a completed handoff`() {
        var completed = false
        var proofs = 0
        val flow =
            PartyFlow({ path, body, _ ->
                when (path) {
                    "presence" ->
                        PartyProtocol.parse(state)
                            .apply {
                                addProperty("chat_party_id", party.id)
                                if (completed) add("party", com.google.gson.JsonNull.INSTANCE)
                            }
                            .toString()
                    "roster" -> {
                        completed = true
                        """{"version":1,"party":null}"""
                    }
                    else -> auth(path, body)
                }
            })
        val result =
            flow.exchange(
                uuid,
                "Alpha",
                receipt,
                true,
                PartyReport(party, GameRoster("Alpha", party.members.map { it.name })),
            ) {
                proofs++
            }
        assertNull(result.party)
        assertEquals(party.id, flow.chatAccess()?.partyId)
        assertEquals(token, flow.chatAccess()?.token)
        assertEquals(uuid, flow.chatAccess()?.account)
        flow.exchange(uuid, "Alpha", receipt, true, null) { proofs++ }
        assertEquals(1, proofs)
        assertNotNull(flow.chatAccess())
        flow.clear()
        assertNull(flow.chatAccess())
    }

    @Test
    fun `old backends do not enable chat and invalid chat party ids are rejected`() {
        val flow = PartyFlow({ path, body, _ -> auth(path, body) })
        flow.exchange(uuid, "Alpha", receipt, true, null) {}
        assertNull(flow.chatAccess())
        val response = PartyProtocol.parse(state)
        response.addProperty("chat_party_id", "https://other.invalid")
        assertFails { PartyProtocol.reply(response) }
    }
}
