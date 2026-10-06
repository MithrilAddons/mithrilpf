package dev.mithril.mithrilpf.update

import com.google.gson.JsonParser
import java.net.URI

data class ReleaseVersion(val text: String, private val parts: List<Int>) :
    Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        parts.zip(other.parts).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        return 0
    }

    val prerelease: Boolean
        get() = parts[3] != 3

    companion object {
        private val pattern =
            Regex(
                "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(alpha|beta|rc)\\.([1-9][0-9]*))?"
            )

        fun parse(text: String): ReleaseVersion? {
            if (text.length > 64) return null
            val match = pattern.matchEntire(text) ?: return null
            val core = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
            val phase = listOf("alpha", "beta", "rc", "").indexOf(match.groupValues[4])
            val number = if (phase == 3) 0 else match.groupValues[5].toIntOrNull() ?: return null
            return ReleaseVersion(text, core + listOf(phase, number))
        }
    }
}

/** [notes] are untrusted display text; only the signed fields authorize an install. */
data class UpdateRelease(
    val version: ReleaseVersion,
    val uri: URI,
    val size: Long,
    val sha256: String,
    val notes: String = "",
) {
    val signatureUri: URI
        get() = URI("$uri.sig")

    val page: URI
        get() = URI("${UpdateCatalog.REPO}/releases/tag/v${version.text}")
}

object UpdateCatalog {
    const val REPO = "https://github.com/MithrilAddons/mithrilpf"
    const val API = "https://api.github.com/repos/MithrilAddons/mithrilpf/releases"
    const val MAX_JAR = 16 * 1024 * 1024
    const val MAX_NOTES = 8192

    fun parse(json: String, current: String, betas: Boolean): List<UpdateRelease> {
        require(json.length <= 1024 * 1024)
        val installed = requireNotNull(ReleaseVersion.parse(current))
        val root = JsonParser.parseString(json)
        val releases = if (root.isJsonArray) root.asJsonArray.toList() else listOf(root)
        require(releases.size <= 50)
        return releases
            .mapNotNull { element ->
                try {
                    val obj = element.asJsonObject
                    require(obj["draft"].toString() == "false")
                    require(obj["published_at"].isJsonPrimitive)
                    val tag = obj["tag_name"].asString
                    require(tag.startsWith("v"))
                    val version = requireNotNull(ReleaseVersion.parse(tag.substring(1)))
                    require(obj["prerelease"].toString() == version.prerelease.toString())
                    require(version > installed && (betas || !version.prerelease))
                    val name = "mithrilpf-${version.text}.jar"
                    val asset =
                        obj["assets"]
                            .asJsonArray
                            .single { it.asJsonObject["name"]?.asString == name }
                            .asJsonObject
                    require(asset["state"].asString == "uploaded")
                    val url = "$REPO/releases/download/$tag/$name"
                    require(asset["browser_download_url"].asString == url)
                    val size = asset["size"].asString.toLong()
                    require(size in 1..MAX_JAR.toLong())
                    val digest = asset["digest"].asString
                    require(digest.matches(Regex("sha256:[a-f0-9]{64}")))
                    val signature =
                        obj["assets"]
                            .asJsonArray
                            .single {
                                it.asJsonObject["name"]?.asString == "$name.sig"
                            }
                            .asJsonObject
                    require(signature["state"].asString == "uploaded")
                    require(signature["size"].asString.toLong() == UpdateSignature.SIZE.toLong())
                    require(signature["browser_download_url"].asString == "$url.sig")
                    val notes =
                        obj["body"]?.takeIf { it.isJsonPrimitive }?.asString?.take(MAX_NOTES) ?: ""
                    UpdateRelease(version, URI(url), size, digest.substring(7), notes)
                } catch (_: RuntimeException) {
                    null
                }
            }
            .distinctBy { it.version.text }
            .sortedByDescending { it.version }
            .take(5)
    }
}
