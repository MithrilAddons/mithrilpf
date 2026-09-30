package dev.mithril.mithrilpf.sync

import com.google.gson.JsonParser
import dev.mithril.mithrilpf.account.proofFixture
import dev.mithril.mithrilpf.dungeontimer.DungeonBest
import kotlin.test.*

class RecordSyncTest {
    private val uuid = "0123456789abcdef0123456789abcdef"
    private val player = "01234567-89ab-cdef-0123-456789abcdef"

    @Test
    fun `worker processing rejects orphan updates clears unlinked state and sends only new events`() {
        var calls = 0
        val flow =
            RecordSyncFlow({ path, body, _ ->
                calls++
                if (path == "sync-challenge") proofFixture(body, "sync", "c".repeat(43))
                else
                    """{"version":1,"user":{"uuid":"$uuid"},"sync_token":"${"t".repeat(43)}","expires_in_seconds":900}"""
            })
        var reports = 0
        val live =
            LiveRecordFlow({ path, _, _ ->
                assertEquals("terminal-report", path)
                reports++
                """{"version":2,"status":"accepted"}"""
            })
        var proofs = 0
        fun process(receipt: String?, event: RecordEvent?) =
            flow.process(uuid, "Player", receipt, event, live) { proofs++ }
        val receipt = "r".repeat(43)
        val orphan = RecordEvent(1, "solo-progress", "{}", 0)
        assertEquals("local_only", process(receipt, orphan))
        assertEquals("unlinked", process(null, null))
        assertEquals(0, calls)
        assertNull(process(receipt, null))
        assertEquals(1, proofs)
        assertEquals(0, reports)
        assertEquals("synced", process(receipt, RecordEvent(1, "terminal-report", "{}", 0)))
        assertEquals(1, reports)
        assertEquals("unlinked", process(null, null))
        assertNull(process(receipt, null))
        assertEquals(2, proofs)
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
    fun `authentication is prepared before a run and reused until expiry`() {
        var now = 0L
        var challenges = 0
        var proofs = 0
        val credential = "t".repeat(43)
        val flow =
            RecordSyncFlow(
                { path, body, bearer ->
                    assertNull(bearer)
                    when (path) {
                        "sync-challenge" -> {
                            challenges++
                            proofFixture(body, "sync", "c".repeat(43))
                        }
                        "sync-verify" ->
                            """{"version":1,"user":{"uuid":"$uuid"},"sync_token":"$credential","expires_in_seconds":900}"""
                        else -> error("Authentication must not upload stored minima")
                    }
                },
                { now },
            )
        repeat(2) {
            assertEquals(credential, flow.authenticate(uuid, "Player", "r".repeat(43)) { proofs++ })
        }
        assertEquals(1, challenges)
        assertEquals(1, proofs)
        now = 900_000_000_000L
        flow.authenticate(uuid, "Player", "r".repeat(43)) { proofs++ }
        assertEquals(2, proofs)
        flow.clear()
        flow.authenticate(uuid, "Player", "r".repeat(43)) { proofs++ }
        assertEquals(3, proofs)
    }
}
