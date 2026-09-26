package dev.mithril.mithrilpf.update

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import net.fabricmc.loader.api.Version
import net.fabricmc.loader.api.metadata.version.VersionPredicate

object UpdateArtifact {
    fun hash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= UpdateCatalog.MAX_JAR)
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun compatible(path: Path, release: UpdateRelease, installed: Map<String, String>): Boolean {
        require(Files.size(path) == release.size && hash(path) == release.sha256) {
            "Update checksum mismatch"
        }
        ZipFile(path.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("fabric.mod.json"))
            val bytes = zip.getInputStream(entry).use { it.readNBytes(65537) }
            require(bytes.size <= 65536)
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(
                root["id"].asString == "mithrilpf" &&
                    root["version"].asString == release.version.text
            )
            require(root["environment"].asString in setOf("client", "*"))
            val deps = root["depends"].asJsonObject
            // Never silently add/update another mod or the game runtime.
            if (!deps.keySet().containsAll(setOf("minecraft", "java", "fabricloader"))) return false
            fun matches(value: com.google.gson.JsonElement, version: String): Boolean {
                val constraints =
                    if (value.isJsonArray) value.asJsonArray.map { it.asString }
                    else listOf(value.asString)
                require(constraints.size in 1..16)
                return constraints.any { VersionPredicate.parse(it).test(Version.parse(version)) }
            }
            if (
                !deps.entrySet().all { (id, constraint) ->
                    installed[id]?.let { matches(constraint, it) } == true
                }
            )
                return false
            for (field in listOf("breaks", "conflicts")) {
                val entries = root[field]?.asJsonObject?.entrySet() ?: continue
                if (
                    entries.any { (id, constraint) ->
                        installed[id]?.let { matches(constraint, it) } == true
                    }
                )
                    return false
            }
            return true
        }
    }
}
