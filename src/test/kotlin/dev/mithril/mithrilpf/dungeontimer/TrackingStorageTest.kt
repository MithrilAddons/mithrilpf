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
        TrackingStorage(directory, publications::add).use { store ->
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
        TrackingStorage(directory, publications::add).use { store ->
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
        TrackingStorage(directory, publications::add).use { store ->
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
}
