package dev.mithril.mithrilpf.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.mithril.mithrilpf.dungeontimer.DungeonBest
import kotlin.test.*

class RecordSyncTest {
    private val uuid = "0123456789abcdef0123456789abcdef"
    private val player = "01234567-89ab-cdef-0123-456789abcdef"
    private val receipt = "r".repeat(43)
    private val token = "t".repeat(43)
    private val challenge =
        """{"version":1,"challenge_id":"${"c".repeat(43)}","server_id":"${"a".repeat(39)}"}"""
    private val snapshot = RecordSnapshot(listOf(SyncedTiming("F7", "solo_clear", 300000, 5800)))
    private val calls = mutableListOf<String>()
    private var now = 0L
    private var failUpload = false
    private var wrongIdentity = false

    private fun post(path: String, body: JsonObject, bearer: String?): String {
        calls += path
        val identity = if (wrongIdentity) "f".repeat(32) else uuid
        when (path) {
            "sync-challenge" -> {
                assertNull(bearer)
                assertEquals(receipt, body["receipt_token"].asString)
                return challenge
            }
            "sync-verify" -> {
                assertNull(bearer)
                return """{"version":1,"sync_token":"$token","expires_in_seconds":900,"user":{"uuid":"$identity"}}"""
            }
            else -> {
                assertEquals("sync-records", path)
                assertEquals(token, bearer)
                assertEquals(setOf("version", "records"), body.keySet())
                if (failUpload) error("Synthetic outage")
                return """{"version":1,"user":{"uuid":"$identity"},"accepted":${body.getAsJsonArray("records").size()}}"""
            }
        }
    }

    @Test
    fun `snapshot shares web fixture and only includes selected account and supported bests`() {
        val saved =
            mapOf(
                "solo" to
                    mapOf(
                        player to
                            mapOf(
                                "F7" to mapOf("300 Score" to DungeonBest(300000, 5800)),
                                "M7" to mapOf("300 Score" to DungeonBest(320000, 6200)),
                            )
                    ),
                "splits" to
                    mapOf(
                        player to
                            mapOf(
                                "F7" to
                                    mapOf(
                                        "Terminals" to DungeonBest(40000, 780),
                                        "Total" to DungeonBest(1, 1),
                                    ),
                                "M7" to mapOf("Terminals" to DungeonBest(42000, 800)),
                            )
                    ),
            )
        val fixture =
            javaClass.getResourceAsStream("/contracts/mod-records-v1.json")!!.bufferedReader().use {
                it.readText()
            }
        assertEquals(JsonParser.parseString(fixture), RecordSnapshot.from(saved, player).json())
        assertTrue(RecordSnapshot.from(saved, "another-player").records.isEmpty())
        val invalid =
            mapOf("solo" to mapOf(player to mapOf("F7" to mapOf("300 Score" to DungeonBest(0, 1)))))
        assertTrue(RecordSnapshot.from(invalid, player).records.isEmpty())
    }

    @Test
    fun `fresh proof precedes upload unchanged snapshots do not generate network`() {
        val flow = RecordSyncFlow(::post) { now }
        flow.sync(uuid, "TestPlayer", receipt, snapshot) { calls += "prove" }
        assertEquals(listOf("sync-challenge", "prove", "sync-verify", "sync-records"), calls)
        calls.clear()
        flow.sync(uuid, "TestPlayer", receipt, snapshot) { error("Unchanged") }
        assertTrue(calls.isEmpty())
        val improved =
            snapshot.copy(records = snapshot.records.map { it.copy(realMillis = 290000) })
        flow.sync(uuid, "TestPlayer", receipt, improved) { error("Reuse scoped session") }
        assertEquals(listOf("sync-records"), calls)
        now = 900_000_000_000
        calls.clear()
        flow.sync(uuid, "TestPlayer", receipt, snapshot) { calls += "prove" }
        assertEquals(listOf("sync-challenge", "prove", "sync-verify", "sync-records"), calls)
    }

    @Test
    fun `failures retain unsent snapshot for retry and reauthenticate`() {
        val flow = RecordSyncFlow(::post)
        failUpload = true
        assertFails { flow.sync(uuid, "TestPlayer", receipt, snapshot) {} }
        failUpload = false
        calls.clear()
        flow.sync(uuid, "TestPlayer", receipt, snapshot) {}
        assertEquals(listOf("sync-challenge", "sync-verify", "sync-records"), calls)
        flow.clear()
        calls.clear()
        flow.sync(uuid, "TestPlayer", receipt, snapshot) {}
        assertEquals(listOf("sync-challenge", "sync-verify", "sync-records"), calls)
    }

    @Test
    fun `different verified identity cannot upload and failed proof cannot verify`() {
        wrongIdentity = true
        assertFails { RecordSyncFlow(::post).sync(uuid, "TestPlayer", receipt, snapshot) {} }
        assertFalse("sync-records" in calls)
        calls.clear()
        assertFails {
            RecordSyncFlow(::post).sync(uuid, "TestPlayer", receipt, snapshot) {
                error("No ownership")
            }
        }
        assertEquals(listOf("sync-challenge"), calls)
    }

    @Test
    fun `invalid challenge and cancellation never reach upload`() {
        val flow =
            RecordSyncFlow({ _, _, _ ->
                challenge.replace("a".repeat(39), "https://invalid.example")
            })
        assertFails { flow.sync(uuid, "TestPlayer", receipt, snapshot) { error("Must not prove") } }
        val cancelled = RecordSyncFlow(::post)
        try {
            Thread.currentThread().interrupt()
            assertFails { cancelled.sync(uuid, "TestPlayer", receipt, snapshot) {} }
            assertTrue(calls.isEmpty())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `account switch proves the new account even for identical records`() {
        val flow = RecordSyncFlow(::post)
        flow.sync(uuid, "TestPlayer", receipt, snapshot) {}
        calls.clear()
        wrongIdentity = true
        flow.sync("f".repeat(32), "OtherPlayer", receipt, snapshot) { calls += "prove" }
        assertEquals(listOf("sync-challenge", "prove", "sync-verify", "sync-records"), calls)
    }

    @Test
    fun `partial acknowledgement is not treated as a successful snapshot`() {
        var partial = true
        val flow =
            RecordSyncFlow({ path, body, bearer ->
                val response = post(path, body, bearer)
                if (partial && path == "sync-records")
                    response.replace("\"accepted\":1", "\"accepted\":0")
                else response
            })
        assertFails { flow.sync(uuid, "TestPlayer", receipt, snapshot) {} }
        partial = false
        calls.clear()
        flow.sync(uuid, "TestPlayer", receipt, snapshot) {}
        assertEquals(listOf("sync-challenge", "sync-verify", "sync-records"), calls)
    }
}
