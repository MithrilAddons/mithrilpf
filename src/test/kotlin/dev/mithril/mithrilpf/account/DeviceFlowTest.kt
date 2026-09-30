package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceFlowTest {
    private val uuid = "a".repeat(32)

    @Test
    fun usesDistinctNonceBoundProofAndValidatesIdentity() {
        var proofHash = ""
        var joined = false
        val calls = mutableListOf<String>()
        val flow = DeviceFlow { path, body, token ->
            calls += path
            assertEquals(null, token)
            if (path.endsWith("challenge")) {
                assertEquals(uuid, body!!.get("uuid").asString)
                val input =
                    "mithrilpf:ownership:v2:device:$uuid:${body.get("client_nonce").asString}:${"b".repeat(64)}"
                proofHash =
                    MessageDigest.getInstance("SHA-256")
                        .digest(input.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                        .take(39)
                challenge(proofHash).toString()
            } else {
                assertTrue(joined)
                assertEquals("c".repeat(43), body!!.get("challenge_id").asString)
                verified(uuid).toString()
            }
        }
        val session =
            flow.signIn(uuid, "Synthetic") {
                assertEquals(proofHash, it)
                joined = true
            }
        assertEquals(listOf("auth/device-challenge", "auth/device-verify"), calls)
        assertEquals("d".repeat(43), session.token)
        assertEquals("e".repeat(43), session.receipt)
        assertTrue(session.expires > System.currentTimeMillis() / 1000)
        assertFalse(session.toString().contains(session.token))
    }

    @Test
    fun rejectsServerChosenHashBeforeContactingMojang() {
        var joined = false
        val flow = DeviceFlow { _, _, _ -> challenge("f".repeat(39)).toString() }
        assertFailsWith<IllegalArgumentException> {
            flow.signIn(uuid, "Synthetic") { joined = true }
        }
        assertFalse(joined)
    }

    @Test
    fun sessionCheckRejectsSubstitutedAccountAndSignOutUsesOnlyDeviceToken() {
        val session = DeviceSession("d".repeat(43), "e".repeat(43), Long.MAX_VALUE)
        val flow = DeviceFlow { path, body, token ->
            assertEquals(session.token, token)
            if (path.endsWith("session")) {
                assertEquals(null, body)
                verified("b".repeat(32)).toString()
            } else {
                assertEquals("auth/device-logout", path)
                assertEquals(JsonObject(), body)
                "{\"version\":1}"
            }
        }
        assertFailsWith<IllegalArgumentException> { flow.check(uuid, session) }
        flow.signOut(session)
    }

    @Test
    fun signOutCanRecoverALostReplyButDoesNotHideServiceFailures() {
        val session = DeviceSession("d".repeat(43), "e".repeat(43), Long.MAX_VALUE)
        DeviceFlow { _, _, _ -> throw ServiceFailure(401) }.signOut(session)
        assertFailsWith<ServiceFailure> {
            DeviceFlow { _, _, _ -> throw ServiceFailure(503) }.signOut(session)
        }
    }

    private fun challenge(hash: String) =
        JsonObject().apply {
            addProperty("version", 1)
            addProperty("challenge_id", "c".repeat(43))
            addProperty("server_nonce", "b".repeat(64))
            addProperty("server_id", hash)
        }

    private fun verified(account: String) =
        JsonObject().apply {
            addProperty("version", 1)
            addProperty("device_token", "d".repeat(43))
            addProperty("receipt_token", "e".repeat(43))
            addProperty("expires_in_seconds", 1000)
            add("user", JsonObject().apply { addProperty("uuid", account) })
        }
}
