package dev.mithril.mithrilpf.update

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

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

    /**
     * Verifies the staged JAR, then lists unmet requirements such as `hypixel-mod-api >=1.0.2`. An
     * empty list means it can be installed.
     */
    fun unmet(
        path: Path,
        release: UpdateRelease,
        installed: Map<String, String>,
    ): List<Requirement> {
        require(Files.size(path) == release.size && hash(path) == release.sha256) {
            "Update checksum mismatch"
        }
        ZipFile(path.toFile()).use { zip ->
            require(
                UpdateDistribution.official(
                    zip.getEntry("assets/mithrilpf/build.properties")?.let {
                        zip.getInputStream(it)
                    },
                    release.version.text,
                )
            ) {
                "Update is not a release build"
            }
            val entry = requireNotNull(zip.getEntry("fabric.mod.json"))
            val bytes = zip.getInputStream(entry).use { it.readNBytes(65537) }
            require(bytes.size <= 65536)
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(
                root["id"].asString == "mithrilpf" &&
                    root["version"].asString == release.version.text
            )
            require(root["environment"].asString in setOf("client", "*"))
            // Never silently add/update the game runtime.
            val base = setOf("minecraft", "java", "fabricloader")
            val deps = root["depends"].asJsonObject
            if (!deps.keySet().containsAll(base))
                return (base - deps.keySet()).map { Requirement(it, listOf("*")) }
            return unmet(root, installed)
        }
    }
}
