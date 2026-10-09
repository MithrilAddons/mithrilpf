package dev.mithril.mithrilpf.games

import java.nio.file.Files
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CuratorCatalogStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private val catalog = Catalog("3:1", listOf(CatalogItem("SYNTHESIZER_V3", "Synthesizer v3")))

    @Test
    fun `the item list survives a restart`() {
        val path = temporary.root.toPath().resolve("mithrilpf/curator.json")
        assertNull(CuratorCatalogStore(path).load())
        CuratorCatalogStore(path).save(catalog)
        assertEquals(catalog, CuratorCatalogStore(path).load())
    }

    @Test
    fun `a file this version can't read is never replaced`() {
        val path = temporary.root.toPath().resolve("curator.json")
        val newer = """{"version":2,"catalog":"9:9","items":[],"future":true}"""
        Files.writeString(path, newer)
        val store = CuratorCatalogStore(path)
        assertNull(store.load())
        store.save(catalog)
        assertEquals(newer, Files.readString(path))
    }
}
