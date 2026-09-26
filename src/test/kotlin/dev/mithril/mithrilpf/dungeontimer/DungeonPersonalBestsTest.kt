package dev.mithril.mithrilpf.dungeontimer

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DungeonPersonalBestsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory: Path
        get() = temporary.root.toPath()

    @Test
    fun `records keep independent real and tick pbs separated by player floor and mode`() {
        val store = DungeonPersonalBests(directory).apply { load() }
        store.record("alice", "F7", mapOf("Maxor" to SplitTime(12000, 200)))
        store.record("alice", "F7", mapOf("Maxor" to SplitTime(11000, 220)))
        store.record("alice", "F7", mapOf("Maxor" to SplitTime(14000, 180)))
        store.record("alice", "M7", mapOf("Maxor" to SplitTime(20000, 300)))
        store.record("bob", "F7", mapOf("Maxor" to SplitTime(30000, 400)))
        assertTrue(store.record("alice", "F7", mapOf("Maxor" to SplitTime(14000, 220))).isEmpty())
        val loaded = DungeonPersonalBests(directory).apply { load() }
        assertEquals(DungeonBest(11000, 180), loaded.records["alice"]?.get("F7")?.get("Maxor"))
        assertEquals(DungeonBest(20000, 300), loaded.records["alice"]?.get("M7")?.get("Maxor"))
        assertEquals(DungeonBest(30000, 400), loaded.records["bob"]?.get("F7")?.get("Maxor"))
    }

    @Test
    fun `tick only pb notification uses this runs real time not the historical best`() {
        val store = DungeonPersonalBests(directory).apply { load() }
        store.record("alice", "F7", mapOf("Watcher Clear" to SplitTime(66160, 1438)))
        val run = SplitTime(75130, 1397)
        val notifications = store.record("alice", "F7", mapOf("Watcher Clear" to run))
        assertEquals(run, notifications["Watcher Clear"])
        val reloaded = DungeonPersonalBests(directory).apply { load() }
        assertEquals(
            DungeonBest(66160, 1397),
            reloaded.records["alice"]?.get("F7")?.get("Watcher Clear"),
        )
    }

    @Test
    fun `real only pb notification uses this runs ticks not the historical best`() {
        val store = DungeonPersonalBests(directory).apply { load() }
        store.record("alice", "F7", mapOf("Maxor" to SplitTime(15000, 200)))
        val run = SplitTime(14000, 220)
        assertEquals(run, store.record("alice", "F7", mapOf("Maxor" to run))["Maxor"])
        assertEquals(DungeonBest(14000, 200), store.records["alice"]?.get("F7")?.get("Maxor"))
        assertTrue(store.record("alice", "F7", mapOf("Maxor" to run)).isEmpty())
    }

    @Test
    fun `zero duration or missing tick measurements are not records`() {
        val store = DungeonPersonalBests(directory).apply { load() }
        assertTrue(store.record("alice", "F7", mapOf("Boss" to SplitTime(0, 20))).isEmpty())
        assertTrue(store.record("alice", "F7", mapOf("Boss" to SplitTime(1000, 0))).isEmpty())
    }

    @Test
    fun `tampered records fail integrity without overwriting existing file`() {
        val store = DungeonPersonalBests(directory).apply { load() }
        store.record("alice", "M7", mapOf("Boss" to SplitTime(10000, 200)))
        val file = directory.resolve("dungeon-pbs.dat")
        val original = Files.readString(file)
        val tampered = original.replaceRange(3, 4, if (original[3] == 'A') "B" else "A")
        Files.writeString(file, tampered)
        assertFailsWith<IllegalArgumentException> { DungeonPersonalBests(directory).load() }
        assertEquals(tampered, Files.readString(file))
    }

    @Test
    fun `missing key does not silently reset records`() {
        val store = DungeonPersonalBests(directory).apply { load() }
        store.record("alice", "M7", mapOf("Boss" to SplitTime(10000, 200)))
        Files.delete(directory.resolve("dungeon-pbs.key"))
        assertFailsWith<IllegalStateException> { DungeonPersonalBests(directory).load() }
        assertTrue(Files.exists(directory.resolve("dungeon-pbs.dat")))
    }
}
