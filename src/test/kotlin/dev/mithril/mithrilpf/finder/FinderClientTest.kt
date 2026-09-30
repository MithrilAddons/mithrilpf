package dev.mithril.mithrilpf.finder

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.mithril.mithrilpf.account.ServiceFailure
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class FinderClientTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture() =
        javaClass.getResourceAsStream("/contracts/party-v1.json")!!.bufferedReader().use {
            JsonParser.parseString(it.readText()).asJsonObject
        }

    private class Host : FinderHost {
        override var uuid = "fedcba9876543210fedcba9876543210"
        override var token: String? = "test-token"
        override var accountBusy = false
        override var inGame = false
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val notices = mutableListOf<FinderNotice>()
        var reloads = 0

        override fun execute(action: () -> Unit) {
            callbacks.add(action)
        }

        override fun reloadAccount() {
            reloads++
        }

        override fun notify(notices: List<FinderNotice>) {
            this.notices += notices
        }

        fun apply(count: Int = 1) {
            repeat(count) {
                assertNotNull(callbacks.poll(5, TimeUnit.SECONDS), "worker completion")()
            }
        }
    }

    private fun store() = FinderPresetStore(temporary.root.toPath().resolve("finder.json"))

    private fun rows(floor: String = "M7") =
        JsonObject()
            .apply {
                addProperty("version", 1)
                addProperty("floor", floor)
                add(
                    "parties",
                    com.google.gson.JsonArray().apply {
                        add(fixture().getAsJsonObject("state").get("party"))
                    },
                )
            }
            .toString()

    @Test
    fun stateListingsSelectionAndPresetsPublishOnlyOnClientThread() {
        val host = Host()
        val calls = LinkedBlockingQueue<String>()
        FinderClient(host, store()) { path, _, token ->
                assertEquals(host.token, token)
                calls.add(path)
                when {
                    path.endsWith("state") -> fixture().get("state").toString()
                    path.contains("?floor=") -> rows(path.substringAfter("?floor="))
                    else ->
                        fixture()
                            .getAsJsonObject("state")
                            .getAsJsonObject("party")
                            .apply { addProperty("version", 1) }
                            .toString()
                }
            }
            .use { client ->
                client.tick(true)
                assertNull(client.state)
                host.apply(3)
                assertEquals("Sable", client.state?.name)
                assertEquals(1, client.listings.size)
                assertTrue(client.presetsLoaded)
                client.tick(false)
                client.tick(false)
                assertEquals(
                    0,
                    host.notices.size,
                ) // Existing notices are not replayed on first connection.
                client.select("party0000001")
                client.tick(true)
                host.apply()
                assertEquals("party0000001", client.detail?.id)
                val revision = client.revision
                client.select("party0000001")
                assertEquals(revision, client.revision)
                client.clearSelection()
                assertNull(client.detail)
                client.changeFloor("F7")
                assertTrue(client.listings.isEmpty())
                client.changeFloor("F7")
                assertFailsWith<IllegalArgumentException> { client.changeFloor("F6") }
                assertFailsWith<IllegalArgumentException> { client.select("invalid") }
                assertTrue(calls.contains("party/client/listings/party0000001"))
            }
    }

    @Test
    fun newerActionWinsOverHeldStateAndListingReplies() {
        val host = Host()
        FinderClient(host, store()) { path, _, _ ->
                if (path.contains("?floor=")) rows()
                else
                    fixture()
                        .getAsJsonObject("state")
                        .apply {
                            addProperty("state_version", if (path.endsWith("state")) 3 else 4)
                        }
                        .toString()
            }
            .use { client ->
                client.tick(true)
                // Leave earlier state/list responses queued while a mutation completes.
                val old = List(3) { assertNotNull(host.callbacks.poll(5, TimeUnit.SECONDS)) }
                client.action("pause", JsonObject())
                host.apply()
                assertEquals(4, client.state?.version)
                old.forEach { it() }
                assertEquals(4, client.state?.version)
                assertTrue(client.listings.isEmpty())
            }
    }

    @Test
    fun accountChangeDiscardsQueuedActionsReadsAndNotifications() {
        val host = Host()
        FinderClient(host, store()) { path, _, _ ->
                if (path.contains("?floor=")) rows() else fixture().get("state").toString()
            }
            .use { client ->
                client.tick(true)
                val old = List(3) { assertNotNull(host.callbacks.poll(5, TimeUnit.SECONDS)) }
                var completed = false
                client.action("reserve", JsonObject()) { completed = true }
                val action = assertNotNull(host.callbacks.poll(5, TimeUnit.SECONDS))
                host.uuid = "a".repeat(32)
                host.token = null
                client.tick(false)
                old.forEach { it() }
                action()
                assertNull(client.state)
                assertTrue(client.listings.isEmpty())
                assertFalse(client.presetsLoaded)
                assertFalse(client.busy)
                assertFalse(completed)
                client.action("leave", JsonObject()) { fail("signed-out action ran") }
            }
    }

    @Test
    fun successfulPublishPersistsOnlyRequestedFloorAndReportKeepsState() {
        val host = Host()
        val store = store()
        val preset =
            FinderPreset(
                FinderRules(mapOf(FinderMetric.CATACOMBS to 45)),
                DungeonRole.MAGE,
                false,
                DungeonRole.entries,
            )
        FinderClient(host, store) { path, _, _ ->
                if (path.endsWith("chat/report")) """{"version":1}"""
                else fixture().get("state").toString()
            }
            .use { client ->
                client.tick(false)
                val body = JsonObject().apply { addProperty("floor", "F7") }
                client.action("publish", body, preset)
                body.addProperty("floor", "M7")
                client.action("leave", JsonObject()) { fail("busy action ran") }
                host.apply()
                assertEquals(preset.rules.shared, store.load(host.uuid)["F7"]?.rules?.shared)
                assertNull(store.load(host.uuid)["M7"])
                assertEquals(preset, client.presets["F7"])
                val state = client.state
                client.action("chat/report", JsonObject())
                host.apply()
                assertEquals(state, client.state)
                assertFalse(client.presetError)
            }
    }

    @Test
    fun failuresPreserveStateExplainErrorsAndRecheckExpiredCredentials() {
        val host = Host()
        var failure: Exception? = null
        FinderClient(host, store()) { _, _, _ ->
                failure?.let { throw it }
                fixture().get("state").toString()
            }
            .use { client ->
                client.tick(false)
                client.action("pause", JsonObject())
                host.apply()
                val state = client.state
                for ((status, expected) in
                    listOf(
                        401 to "expired",
                        403 to "restricted",
                        409 to "changed",
                        422 to "invalid",
                        429 to "limited",
                        503 to "unavailable",
                    )) {
                    failure = ServiceFailure(status)
                    var success = true
                    client.action("pause", JsonObject()) { success = it }
                    host.apply()
                    assertFalse(success)
                    assertEquals(expected, client.error)
                    assertEquals(status >= 500, client.offline)
                    assertEquals(state, client.state)
                }
                assertEquals(1, host.reloads)
                failure = IllegalStateException("synthetic failure")
                client.action("pause", JsonObject())
                host.apply()
                assertTrue(client.offline)
                failure = null
                client.action("pause", JsonObject())
                host.apply()
                assertFalse(client.offline)
                assertNull(client.error)
            }
    }

    @Test
    fun missingDetailAndFloorChangesCannotRestoreStaleSelection() {
        val host = Host()
        FinderClient(host, store()) { path, _, _ ->
                when {
                    path.endsWith("state") -> """{"version":1,"unchanged":true}"""
                    path.contains("?floor=") -> rows(path.substringAfter("?floor="))
                    else -> throw ServiceFailure(404)
                }
            }
            .use { client ->
                client.tick(false)
                client.select("party0000001")
                client.tick(true)
                host.apply(3)
                assertNull(client.detail)
                assertNull(client.error)
                client.refresh()
                client.tick(true)
                val old = List(2) { assertNotNull(host.callbacks.poll(5, TimeUnit.SECONDS)) }
                client.changeFloor("F7")
                old.forEach { it() }
                assertTrue(client.listings.isEmpty())
                assertNull(client.selected)
            }
    }

    @Test
    fun malformedPresetsRemainUntouchedWhenActionSucceeds() {
        val host = Host()
        val path = temporary.root.toPath().resolve("finder.json")
        java.nio.file.Files.writeString(path, "broken")
        val preset = FinderPreset(FinderRules(), DungeonRole.MAGE, false, DungeonRole.entries)
        FinderClient(host, FinderPresetStore(path)) { route, _, _ ->
                if (route.contains("?floor=")) rows() else fixture().get("state").toString()
            }
            .use { client ->
                client.tick(true)
                host.apply(3)
                assertTrue(client.presetError)
                client.action("publish", JsonObject(), preset)
                host.apply()
                assertTrue(client.presetError)
                assertEquals("broken", java.nio.file.Files.readString(path))
                assertNotNull(client.state)
            }
    }
}
