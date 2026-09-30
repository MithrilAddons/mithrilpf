package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.mithril.mithrilpf.party.PartyFlow
import dev.mithril.mithrilpf.sync.RecordSyncFlow
import kotlin.test.*

class OwnershipProofTest {
    private val uuid = "0".repeat(32)
    private val token = "a".repeat(43)

    @Test
    fun `all proof flows reject substituted valid login hashes before calling Mojang`() {
        val flows: List<((String, JsonObject) -> String, (String) -> Unit) -> Unit> =
            listOf(
                { post, prove ->
                    LinkFlow(post).run(uuid, "Player", prove)
                },
                { post, prove ->
                    RecordSyncFlow({ path, body, _ -> post(path, body) })
                        .authenticate(
                            uuid,
                            "Player",
                            token,
                            prove,
                        )
                },
                { post, prove ->
                    PartyFlow({ path, body, _ -> post(path, body) })
                        .exchange(uuid, "Player", token, false, null, prove)
                },
            )
        for (flow in flows) {
            var proved = false
            var requests = 0
            assertFails {
                flow(
                    { _, _ ->
                        requests++
                        """{"version":1,"challenge_id":"$token","server_id":"${"b".repeat(39)}","server_nonce":"${"c".repeat(64)}"}"""
                    },
                    { proved = true },
                )
            }
            assertFalse(proved)
            assertEquals(1, requests)
        }
    }

    @Test
    fun `proofs bind fresh client entropy account purpose and backend nonce`() {
        val nonce = OwnershipProof.nonce()
        assertTrue(Regex("[0-9a-f]{64}").matches(nonce))
        assertNotEquals(nonce, OwnershipProof.nonce())
        val request =
            JsonObject().apply {
                addProperty("uuid", uuid)
                addProperty("client_nonce", nonce)
            }
        val response = JsonParser.parseString(proofFixture(request, "link", token)).asJsonObject
        assertEquals(
            response["server_id"].asString,
            OwnershipProof.serverId(response, nonce, uuid, "link"),
        )
        assertFails { OwnershipProof.serverId(response, OwnershipProof.nonce(), uuid, "link") }
        assertFails { OwnershipProof.serverId(response, nonce, "1".repeat(32), "link") }
        assertFails { OwnershipProof.serverId(response, nonce, uuid, "party") }
        response.addProperty("server_nonce", "e".repeat(64))
        assertFails { OwnershipProof.serverId(response, nonce, uuid, "link") }
        response.remove("server_nonce")
        assertFails { OwnershipProof.serverId(response, nonce, uuid, "link") }
    }
}
