package dev.mithril.mithrilpf.finder

import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class FinderPresetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val uuid = "a".repeat(32)

    private fun preset(cata: Int) =
        FinderPreset(
            FinderRules(mapOf(FinderMetric.CATACOMBS to cata)),
            DungeonRole.MAGE,
            false,
            DungeonRole.entries.toList(),
        )

    @Test
    fun floorsAndAccountsAreIndependentAndUnknownFieldsSurvive() {
        val path = temporary.root.toPath().resolve("finder.json")
        val store = FinderPresetStore(path)
        store.save(uuid, "F7", preset(30))
        store.save(uuid, "M7", preset(50))
        store.save("b".repeat(32), "F7", preset(40))
        val root = JsonParser.parseString(Files.readString(path)).asJsonObject
        root.addProperty("future", true)
        root
            .getAsJsonObject("accounts")
            .getAsJsonObject(uuid)
            .getAsJsonObject("F7")
            .getAsJsonObject("rules")
            .getAsJsonObject("shared")
            .addProperty("future_metric", 123)
        Files.writeString(path, root.toString())
        store.save(uuid, "F7", preset(32))
        assertEquals(32, store.load(uuid)["F7"]!!.rules.shared[FinderMetric.CATACOMBS])
        assertEquals(50, store.load(uuid)["M7"]!!.rules.shared[FinderMetric.CATACOMBS])
        assertEquals(40, store.load("b".repeat(32))["F7"]!!.rules.shared[FinderMetric.CATACOMBS])
        assertTrue(Files.readString(path).contains("\"future_metric\":123"))
        assertTrue(Files.readString(path).contains("\"future\":true"))
    }

    @Test
    fun malformedAndFutureFilesAreNeverReplaced() {
        val path = temporary.root.toPath().resolve("finder.json")
        for (value in
            listOf(
                "broken",
                "{\"version\":2}",
                "{\"version\":1,\"accounts\":{\"$uuid\":{\"F7\":{}}}}",
            )) {
            Files.writeString(path, value)
            assertFails { FinderPresetStore(path).save(uuid, "F7", preset(30)) }
            assertEquals(value, Files.readString(path))
        }
    }

    @Test
    fun invalidRolesAndThresholdsAreRejected() {
        val store = FinderPresetStore(temporary.root.toPath().resolve("finder.json"))
        assertFails { store.save(uuid, "F7", preset(0)) }
        assertFails {
            store.save(uuid, "F7", preset(30).copy(slots = List(5) { DungeonRole.ARCHER }))
        }
    }
}
