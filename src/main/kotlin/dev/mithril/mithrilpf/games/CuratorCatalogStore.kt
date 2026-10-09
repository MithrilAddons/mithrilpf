package dev.mithril.mithrilpf.games

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Cached guess list, so autocomplete works before the backend answers. A file this version can't
 * read is left untouched rather than replaced. Use only from the games worker.
 */
class CuratorCatalogStore(private val path: Path) {
    private var unreadable = false

    fun load(): Catalog? {
        if (!Files.exists(path)) return null
        return runCatching {
            val bytes = Files.newInputStream(path).use { it.readNBytes(LIMIT + 1) }
            require(bytes.size <= LIMIT)
            CuratorProtocol.catalog(bytes.toString(Charsets.UTF_8), null)
        }
            .onFailure { unreadable = true }
            .getOrNull()
    }

    fun save(catalog: Catalog) {
        if (unreadable) return
        val root =
            JsonObject().apply {
                addProperty("version", 1)
                addProperty("catalog", catalog.version)
                add(
                    "items",
                    JsonArray().apply {
                        for (item in catalog.items) add(
                            JsonArray().apply {
                                add(item.id)
                                add(item.name)
                            }
                        )
                    },
                )
            }
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= LIMIT)
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "curator-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val LIMIT = 2 * 1024 * 1024
    }
}
