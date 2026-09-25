package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LinkFlowTest {
    private val token = "a".repeat(43)
    private val serverId = "b".repeat(39)
    private val challenge = """{"version":1,"challenge_id":"$token","server_id":"$serverId"}"""
    private val verified = """{"version":1,"link_token":"$token"}"""
    private val uuid = "0".repeat(32)

    @Test
    fun `proves ownership before requesting a fixed origin fragment link`() {
        val calls = mutableListOf<String>()
        val flow = LinkFlow { path, body ->
            calls.add(path)
            if (path == "challenge") {
                assertEquals(setOf("version", "uuid", "name"), body.keySet())
                assertEquals(uuid, body["uuid"].asString)
                challenge
            } else {
                assertEquals(setOf("challenge_id"), body.keySet())
                verified
            }
        }
        val issued = flow.run(uuid, "TestPlayer") { calls.add("prove:$it") }
        assertEquals(listOf("challenge", "prove:$serverId", "verify"), calls)
        assertEquals("https://mithril.foo/link#$token", issued.uri.toString())
        assertNull(issued.receipt)
    }

    @Test
    fun `invalid challenges never reach ownership proof`() {
        for (body in
            listOf(
                "{}",
                challenge.replace("\"version\":1", "\"version\":2"),
                challenge.replace(serverId, "https://evil.invalid"),
                "x".repeat(16385),
                challenge.replace(token, "x"),
            )) {
            var proved = false
            assertFails { LinkFlow { _, _ -> body }.run(uuid, "Player") { proved = true } }
            assertFalse(proved)
        }
    }

    @Test
    fun `failed ownership never requests a browser token`() {
        var count = 0
        assertFails {
            LinkFlow { _, _ ->
                    count++
                    challenge
                }
                .run(uuid, "Player") { error("Rejected") }
        }
        assertEquals(1, count)
    }

    @Test
    fun `invalid link cannot select an arbitrary destination`() {
        val flow = LinkFlow { path: String, _: JsonObject ->
            if (path == "challenge") challenge else verified.replace(token, "//evil.invalid")
        }
        assertFails { flow.run(uuid, "Player") {} }
    }

    @Test
    fun `invalid identity fails without a request`() {
        assertFails { LinkFlow { _, _ -> error("Must not call") }.run("bad", "Player") {} }
    }

    @Test
    fun `status is scoped to receipt and rejects a different account`() {
        val response =
            """{"version":1,"status":"linked","user":{"uuid":"$uuid","name":"TestPlayer"}}"""
        val flow = LinkFlow { path, body ->
            assertEquals("link-status", path)
            assertEquals(setOf("token"), body.keySet())
            assertEquals(token, body["token"].asString)
            response
        }
        assertEquals("linked", flow.status(token, uuid))
        assertFails { flow.status(token, "1".repeat(32)) }
        for (state in listOf("pending", "expired")) {
            assertEquals(
                state,
                LinkFlow { _, _ -> """{"version":1,"status":"$state"}""" }.status(token, uuid),
            )
        }
        assertFails {
            LinkFlow { _, _ -> """{"version":1,"status":"unknown"}""" }.status(token, uuid)
        }
    }

    @Test
    fun `receipt is separate from browser link and validated`() {
        val receipt = "c".repeat(43)
        fun run(value: String) = LinkFlow { path, _ ->
            if (path == "challenge") challenge
            else verified.dropLast(1) + """, "receipt_token":"$value"}"""
        }
            .run(uuid, "Player") {}
        assertEquals(receipt, run(receipt).receipt)
        assertEquals("https://mithril.foo/link#$token", run(receipt).uri.toString())
        assertFails { run("bad") }
    }
}
