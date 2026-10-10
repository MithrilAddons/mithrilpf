package dev.mithril.mithrilpf.dungeontimer

import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class TrackingStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory
        get() = temporary.root.toPath()

    private val publications = LinkedBlockingQueue<() -> Unit>()

    private fun publishNext() =
        assertNotNull(publications.poll(10, TimeUnit.SECONDS), "Worker did not publish").invoke()

    @Test
    fun `worker snapshots publish only on caller thread and persist independently per category`() {
        val desired = TrackingSettings(ticks = true, tablePosition = HudPosition(scale = 3.5))
        TrackingStorage(directory, {}, publications::add).use { store ->
            store.load { assertEquals(TrackingSettings(), it) }
            assertFalse(store.ready)
            publishNext()
            assertTrue(store.ready)
            store.settings(desired)
            store.record(
                "splits",
                "synthetic-player",
                "M7",
                mapOf("Blood Open" to SplitTime(22000, 400)),
            ) { before, changed ->
                assertTrue(before.isEmpty())
                assertEquals(SplitTime(22000, 400), changed["Blood Open"])
            }
            assertTrue(store.records["splits"].orEmpty().isEmpty())
            publishNext()
            val previousSnapshot = store.records
            store.record(
                "splits",
                "synthetic-player",
                "M7",
                mapOf("Blood Open" to SplitTime(21000, 420)),
            ) { before, changed ->
                assertEquals(DungeonBest(22000, 400), before["Blood Open"])
                assertEquals(SplitTime(21000, 420), changed["Blood Open"])
            }
            publishNext()
            assertEquals(
                DungeonBest(22000, 400),
                previousSnapshot["splits"]?.get("synthetic-player")?.get("M7")?.get("Blood Open"),
            )
            assertEquals(
                DungeonBest(21000, 400),
                store.records["splits"]?.get("synthetic-player")?.get("M7")?.get("Blood Open"),
            )
            assertTrue(store.records["rooms"].orEmpty().isEmpty())
            assertTrue(store.records["solo"].orEmpty().isEmpty())
        }
        TrackingStorage(directory, {}, publications::add).use { store ->
            store.load { assertEquals(desired, it) }
            publishNext()
            assertEquals(
                DungeonBest(21000, 400),
                store.records["splits"]?.get("synthetic-player")?.get("M7")?.get("Blood Open"),
            )
            assertFalse(store.error)
        }
    }

    @Test
    fun `failed loading preserves bad config and prevents writes`() {
        val file = directory.resolve("tracking.json")
        Files.writeString(file, "bad config")
        TrackingStorage(directory, {}, publications::add).use { store ->
            store.load { fail("Invalid configuration must not publish defaults") }
            publishNext()
            assertTrue(store.error)
            assertFalse(store.ready)
            store.settings(TrackingSettings())
            store.record("rooms", "synthetic", "F7", mapOf("Market" to SplitTime(1000, 20))) { _, _
                ->
                fail()
            }
        }
        assertEquals("bad config", Files.readString(file))
        assertFalse(Files.exists(directory.resolve("rooms")))
    }

    @Test
    fun `a failed save keeps tracking on and the next save tries again`() {
        val notices = mutableListOf<String>()
        TrackingStorage(directory, notices::add, publications::add).use { store ->
            store.load {}
            publishNext()
            // A non-empty folder where the PB file belongs makes the next write fail.
            val blocked = directory.resolve("splits/dungeon-pbs.dat")
            Files.createDirectories(blocked)
            Files.writeString(blocked.resolve("lock"), "x")
            store.record(
                "splits",
                "synthetic",
                "M7",
                mapOf("Blood Open" to SplitTime(22000, 400)),
            ) { _, _ ->
                fail("A failed save must not report a result")
            }
            publishNext()
            assertEquals(1, store.saveFailures)
            assertEquals(listOf("save_error"), notices)
            assertFalse(store.error)
            assertTrue(store.ready)
            assertTrue(store.records["splits"].orEmpty().isEmpty())
            Files.delete(blocked.resolve("lock"))
            Files.delete(blocked)
            var changed = emptyMap<String, SplitTime>()
            store.record(
                "splits",
                "synthetic",
                "M7",
                mapOf("Blood Open" to SplitTime(22000, 400)),
            ) { _, result ->
                changed = result
            }
            publishNext()
            assertEquals(SplitTime(22000, 400), changed["Blood Open"])
            assertEquals(1, store.saveFailures)
        }
        TrackingStorage(directory, {}, publications::add).use { store ->
            store.load {}
            publishNext()
            assertEquals(
                DungeonBest(22000, 400),
                store.records["splits"]?.get("synthetic")?.get("M7")?.get("Blood Open"),
            )
        }
    }

    @Test
    fun `a save the worker can't take counts as a failed save`() {
        val notices = mutableListOf<String>()
        val store = TrackingStorage(directory, notices::add, publications::add)
        store.load {}
        publishNext()
        store.close()
        store.settings(TrackingSettings(ticks = true))
        publishNext()
        assertEquals(1, store.saveFailures)
        assertEquals(listOf("save_error"), notices)
        assertFalse(store.error)
        assertTrue(store.ready)
    }

    @Test
    fun `old solo room secrets become Total when loaded`() {
        DungeonPersonalBests(directory.resolve("rooms")).apply {
            load()
            record(
                "p",
                "F7",
                mapOf(
                    "Room · Cleared" to SplitTime(1000, 20),
                    "Room · Secrets" to SplitTime(2000, 40),
                    "Other · Secrets" to SplitTime(3000, 50),
                    "Other · Total" to SplitTime(2500, 60),
                ),
            )
        }
        val expected =
            mapOf(
                "Room · Cleared" to DungeonBest(1000, 20),
                "Room · Total" to DungeonBest(2000, 40),
                "Other · Total" to DungeonBest(2500, 50),
            )
        TrackingStorage(directory, {}, publications::add).use { store ->
            store.load {}
            publishNext()
            assertEquals(expected, store.records["rooms"]?.get("p")?.get("F7"))
        }
        val saved = DungeonPersonalBests(directory.resolve("rooms")).apply { load() }
        assertEquals(expected, saved.records["p"]?.get("F7"))
    }

    @Test
    fun `room PBs are saved and reported for the player who is playing`() {
        TrackingStorage(directory, {}, publications::add).use { store ->
            store.load {}
            publishNext()
            var player: String? = "p"
            var floor: String? = "F7"
            val sent = mutableListOf<String>()
            val host = RoomPbStorage({ store }, { player }, { floor }) { sent += it.string }
            val counts = mutableListOf<Int>()
            host.save(mapOf("Room · Total" to SplitTime(1000, 20)), counts::add)
            publishNext()
            assertEquals(listOf(1), counts)
            assertEquals(mapOf("Room · Total" to DungeonBest(1000, 20)), host.best())
            host.save(mapOf("Room · Total" to SplitTime(900, 30)), counts::add)
            player = "someone else"
            publishNext()
            assertEquals(listOf(1), counts)
            player = null
            host.save(mapOf("Room · Total" to SplitTime(800, 10)), counts::add)
            player = "p"
            floor = null
            host.save(mapOf("Room · Total" to SplitTime(800, 10)), counts::add)
            assertTrue(publications.isEmpty())
            host.send(net.minecraft.network.chat.Component.literal("hello"))
            assertEquals(listOf("hello"), sent)
        }
    }

    @Test
    fun `saved runs update the statistics`() {
        val player = java.util.UUID(0, 1).toString()
        val times =
            mapOf(
                "Blood Open" to SplitTime(10_000, 200),
                "Watcher Clear" to SplitTime(20_000, 400),
                "Total" to SplitTime(100_000, 2000),
            )
        val run =
            DungeonRunRecord(
                java.util.UUID.randomUUID().toString(),
                player,
                "E",
                1,
                times,
                times,
                0,
            )
        TrackingStorage(directory, {}, publications::add).use { store ->
            store.load {}
            publishNext()
            store.append(run)
            publishNext()
            assertEquals(setOf(player), store.statistics.keys)
        }
        assertTrue(Files.exists(directory.resolve("runs/${run.id}.json")))
    }
}
