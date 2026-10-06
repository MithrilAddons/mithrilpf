package dev.mithril.mithrilpf.update

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Where a signed MithrilPF release says a required mod is published on Modrinth. */
data class DependencySource(val id: String, val project: String, val name: String)

/** A Modrinth release the player may approve; verified again after download. */
data class DependencyOffer(
    val source: DependencySource,
    val requirement: Requirement,
    val version: String,
    val uri: URI,
    val size: Long,
    val sha512: String,
    val filename: String,
)

/**
 * Installs only mods that are missing entirely, from the Modrinth project named by the verified
 * release itself. Releases only; never replaces or updates an installed mod.
 */
object ModrinthDependencies {
    const val API = "https://api.modrinth.com/v2/project/"
    const val MAX_JAR = 32 * 1024 * 1024
    private val PROJECT = Regex("[A-Za-z0-9]{8}")
    private val MOD_ID = Regex("[a-z][a-z0-9_-]{1,63}")
    private val FILENAME = Regex("[A-Za-z0-9._+-]{1,128}\\.jar")

    /**
     * Reads `assets/mithrilpf/dependencies.json` from an already signature-verified release JAR.
     */
    fun sources(release: Path): Map<String, DependencySource> =
        ZipFile(release.toFile()).use { zip ->
            val entry = zip.getEntry("assets/mithrilpf/dependencies.json") ?: return emptyMap()
            val bytes = zip.getInputStream(entry).use { it.readNBytes(16385) }
            require(bytes.size <= 16384)
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(root["version"]?.toString() == "1")
            root["modrinth"].asJsonObject.entrySet().associate { (id, value) ->
                val source = value.asJsonObject
                val project = source["project"].asString
                val name = source["name"].asString
                require(MOD_ID.matches(id) && PROJECT.matches(project) && name.length in 1..64)
                id to DependencySource(id, project, name)
            }
        }

    fun versionsUri(project: String, minecraft: String): URI {
        require(PROJECT.matches(project))
        fun list(value: String) = URLEncoder.encode("[\"$value\"]", Charsets.UTF_8)
        return URI(
            "$API$project/version?loaders=${list("fabric")}&game_versions=${list(minecraft)}"
        )
    }

    /** The newest Modrinth release for [minecraft] whose version meets [requirement], if any. */
    fun pick(
        json: String,
        source: DependencySource,
        requirement: Requirement,
        minecraft: String,
    ): DependencyOffer? {
        require(json.length <= 1024 * 1024)
        return JsonParser.parseString(json)
            .asJsonArray
            .take(100)
            .mapNotNull { element ->
                try {
                    val version = element.asJsonObject
                    require(version["project_id"].asString == source.project)
                    require(version["version_type"].asString == "release")
                    require(version["loaders"].asJsonArray.any { it.asString == "fabric" })
                    require(version["game_versions"].asJsonArray.any { it.asString == minecraft })
                    val number =
                        version["version_number"].asString.also { require(it.length <= 64) }
                    val files = version["files"].asJsonArray.map { it.asJsonObject }
                    val file =
                        files.singleOrNull { it["primary"]?.asBoolean == true } ?: files.single()
                    val name = file["filename"].asString.also { require(FILENAME.matches(it)) }
                    val uri = URI(file["url"].asString)
                    require(
                        uri.scheme == "https" &&
                            uri.host == "cdn.modrinth.com" &&
                            uri.path.startsWith("/data/${source.project}/")
                    )
                    val size = file["size"].asLong.also { require(it in 1..MAX_JAR) }
                    val sha512 =
                        file["hashes"].asJsonObject["sha512"].asString.also {
                            require(it.matches(Regex("[a-f0-9]{128}")))
                        }
                    val published = version["date_published"].asString
                    val offer =
                        DependencyOffer(source, requirement, number, uri, size, sha512, name)
                    // Modrinth version names usually match the mod's; the JAR is rechecked anyway.
                    val suitable = runCatching {
                        satisfies(requirement.constraints, number)
                    }
                        .getOrDefault(true)
                    if (suitable) published to offer else null
                } catch (_: RuntimeException) {
                    null
                }
            }
            .maxByOrNull { it.first }
            ?.second
    }

    /**
     * Checks the download against Modrinth's SHA-512, then its own metadata: the expected mod ID, a
     * version meeting the requirement, and requirements met by [available]. Returns its version.
     */
    fun verify(path: Path, offer: DependencyOffer, available: Map<String, String>): String {
        require(Files.size(path) == offer.size)
        val digest = MessageDigest.getInstance("SHA-512").digest(Files.readAllBytes(path))
        require(digest.joinToString("") { "%02x".format(it) } == offer.sha512) {
            "Dependency checksum mismatch"
        }
        ZipFile(path.toFile()).use { zip ->
            val bytes =
                zip.getInputStream(requireNotNull(zip.getEntry("fabric.mod.json"))).use {
                    it.readNBytes(65537)
                }
            require(bytes.size <= 65536)
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(root["id"].asString == offer.source.id)
            val version = root["version"].asString
            require(satisfies(offer.requirement.constraints, version))
            require(root["environment"]?.asString in setOf(null, "client", "*"))
            require(unmet(root, available).isEmpty())
            return version
        }
    }
}

/** A dependency constraint from fabric.mod.json; [conflict] marks a breaks/conflicts entry. */
data class Requirement(
    val id: String,
    val constraints: List<String>,
    val conflict: Boolean = false,
) {
    override fun toString() = if (conflict) "no $id" else "$id ${constraints.joinToString(" or ")}"
}

internal fun satisfies(constraints: List<String>, version: String): Boolean {
    require(constraints.size in 1..16)
    val parsed = net.fabricmc.loader.api.Version.parse(version)
    return constraints.any {
        net.fabricmc.loader.api.metadata.version.VersionPredicate.parse(it).test(parsed)
    }
}

/** Requirements in a fabric.mod.json root that [installed] mods do not meet. */
internal fun unmet(
    root: com.google.gson.JsonObject,
    installed: Map<String, String>,
): List<Requirement> {
    fun constraints(value: JsonElement) =
        if (value.isJsonArray) value.asJsonArray.map { it.asString } else listOf(value.asString)
    val missing =
        root["depends"]?.asJsonObject?.entrySet().orEmpty().mapNotNull { (id, value) ->
            val requirement = Requirement(id, constraints(value))
            requirement.takeUnless {
                installed[id]?.let { version -> satisfies(requirement.constraints, version) } ==
                    true
            }
        }
    val conflicts =
        listOf("breaks", "conflicts").flatMap { field ->
            root[field]?.asJsonObject?.entrySet().orEmpty().mapNotNull { (id, value) ->
                Requirement(id, constraints(value), conflict = true).takeIf {
                    installed[id]?.let { version -> satisfies(it.constraints, version) } == true
                }
            }
        }
    return missing + conflicts
}
