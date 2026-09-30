package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class NativeAccountTest {
    @get:Rule val temporary = TemporaryFolder()
    private val uuid = "a".repeat(32)
    private val session =
        DeviceSession("d".repeat(43), "e".repeat(43), System.currentTimeMillis() / 1000 + 1000)
    private val callbacks = LinkedBlockingQueue<() -> Unit>()

    private fun apply() = assertNotNull(callbacks.poll(5, TimeUnit.SECONDS), "worker completion")()

    private fun store() = DeviceSessionStore(temporary.root.toPath().resolve("device.json"))

    private fun response(account: String = uuid) =
        """{"version":1,"device_token":"${session.token}","receipt_token":"${session.receipt}","expires_in_seconds":1000,"user":{"uuid":"$account"}}"""

    private fun account(
        store: DeviceSessionStore,
        flow: DeviceFlow,
        current: () -> String = { uuid },
        proof: (String) -> Unit = {},
    ) =
        NativeAccount(
            store,
            flow,
            current,
            { DeviceIdentity("Synthetic", proof) },
            { callbacks.add(it) },
        )

    @Test
    fun loadsOnWorkerAndSignOutRevokesBeforeErasingSavedCredential() {
        val store = store()
        store.save(uuid, session)
        val calls = mutableListOf<String>()
        account(
                store,
                DeviceFlow { path, _, token ->
                    assertEquals(session.token, token)
                    calls += path
                    response()
                },
            )
            .use { account ->
                account.tick()
                assertTrue(account.busy)
                assertNull(account.session)
                account.load() // Does not enqueue duplicate work.
                apply()
                assertEquals("signed_in", account.status)
                assertTrue(account.writable)
                assertEquals(session.token, account.session?.token)
                account.tick()
                account.signIn()
                account.signOut()
                assertEquals("signing_out", account.status)
                account.signOut()
                apply()
                assertNull(account.session)
                assertNull(store.load(uuid))
                assertEquals(listOf("auth/device-session", "auth/device-logout"), calls)
                account.signOut()
                assertFalse(account.busy)
            }
    }

    @Test
    fun expiredCredentialsAreRemovedButOutagesPreserveThem() {
        val store = store()
        store.save(uuid, session)
        var code = 503
        account(store, DeviceFlow { _, _, _ -> throw ServiceFailure(code) }).use { account ->
            account.tick()
            apply()
            assertEquals("failed", account.status)
            assertFalse(account.writable)
            assertNotNull(store.load(uuid))
            code = 401
            account.load()
            apply()
            assertEquals("expired", account.status)
            assertTrue(account.writable)
            assertNull(store.load(uuid))
        }
    }

    @Test
    fun signOutFailureRetainsSessionForRetry() {
        val store = store()
        store.save(uuid, session)
        account(
                store,
                DeviceFlow { path, _, _ ->
                    if (path.endsWith("logout")) throw ServiceFailure(503) else response()
                },
            )
            .use { account ->
                account.tick()
                apply()
                account.signOut()
                apply()
                assertEquals("failed", account.status)
                assertNotNull(account.session)
                assertNotNull(store.load(uuid))
            }
    }

    @Test
    fun accountSwitchDiscardsQueuedCredentialPublication() {
        val store = store()
        store.save(uuid, session)
        var current = uuid
        account(store, DeviceFlow { _, _, _ -> response() }, { current }).use { account ->
            account.tick()
            val old = assertNotNull(callbacks.poll(5, TimeUnit.SECONDS))
            current = "b".repeat(32)
            account.tick()
            old()
            assertNull(account.session)
            assertTrue(account.busy)
            apply()
            assertEquals("signed_out", account.status)
            assertNull(account.session)
        }
    }

    @Test
    fun malformedStorageDisablesSignInWithoutOverwritingFile() {
        val path = temporary.root.toPath().resolve("device.json")
        Files.writeString(path, "broken")
        account(store(), DeviceFlow { _, _, _ -> error("No HTTP expected") }).use { account ->
            account.signIn()
            account.tick()
            apply()
            account.signIn()
            assertFalse(account.writable)
            assertEquals("failed", account.status)
            assertEquals("broken", Files.readString(path))
        }
    }

    private fun signInFlow(revoked: () -> Unit): DeviceFlow = DeviceFlow { path, body, _ ->
        when {
            path.endsWith("challenge") -> {
                val nonce = body!!.get("client_nonce").asString
                val input = "mithrilpf:ownership:v2:device:$uuid:$nonce:${"b".repeat(64)}"
                val hash =
                    MessageDigest.getInstance("SHA-256")
                        .digest(input.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                        .take(39)
                JsonObject()
                    .apply {
                        addProperty("version", 1)
                        addProperty("challenge_id", "c".repeat(43))
                        addProperty("server_nonce", "b".repeat(64))
                        addProperty("server_id", hash)
                    }
                    .toString()
            }
            path.endsWith("logout") -> {
                revoked()
                """{"version":1}"""
            }
            else -> response()
        }
    }

    @Test
    fun successfulProofSavesSessionAndFailedSaveRevokesOrphanCredential() {
        val store = store()
        var revoked = false
        var corrupt = false
        var proofs = 0
        account(
                store,
                signInFlow { revoked = true },
                proof = {
                    proofs++
                    if (corrupt)
                        Files.writeString(temporary.root.toPath().resolve("device.json"), "broken")
                },
            )
            .use { account ->
                account.tick()
                apply()
                account.signIn()
                account.signIn()
                apply()
                assertEquals("signed_in", account.status)
                assertEquals(session.token, store.load(uuid)?.token)
                assertEquals(1, proofs)
                account.signOut()
                apply()
                revoked = false
                corrupt = true
                account.signIn()
                apply()
                assertEquals("failed", account.status)
                assertNull(account.session)
                assertTrue(revoked)
                assertEquals(2, proofs)
            }
    }

    @Test
    fun closeDiscardsAlreadyQueuedPublication() {
        val store = store()
        store.save(uuid, session)
        val account = account(store, DeviceFlow { _, _, _ -> response() })
        account.tick()
        val old = assertNotNull(callbacks.poll(5, TimeUnit.SECONDS))
        account.close()
        old()
        assertNull(account.session)
    }
}
