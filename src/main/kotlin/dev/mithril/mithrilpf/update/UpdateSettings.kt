package dev.mithril.mithrilpf.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class UpdateSettings(val enabled: Boolean = true, val prereleases: Boolean = false)

/** Accessed only by the updater worker. Unknown fields survive writes. */
class UpdateSettingsStore(private val path: Path) {
    private fun read(): JsonObject {
        if (!Files.exists(path)) return JsonObject().apply { addProperty("version", 1) }
        val bytes = Files.newInputStream(path).use { it.readNBytes(16385) }
        require(bytes.size <= 16384)
        return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.also {
            require(it["version"]?.toString() == "1")
            decode(it)
        }
    }

    private fun decode(root: JsonObject): UpdateSettings {
        fun bool(key: String, default: Boolean): Boolean {
            val v = root[key] ?: return default
            require(v.isJsonPrimitive && v.asJsonPrimitive.isBoolean)
            return v.asBoolean
        }
        return UpdateSettings(bool("enabled", true), bool("prereleases", false))
    }

    fun load() = decode(read())

    fun save(settings: UpdateSettings) {
        val root = read()
        root.addProperty("enabled", settings.enabled)
        root.addProperty("prereleases", settings.prereleases)
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "updates-", ".tmp")
        try {
            Files.writeString(temp, root.toString())
            Files.move(
                temp,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
