package dev.mithril.mithrilpf.update

import java.io.InputStream
import java.util.Properties

/** This is build provenance, not a signature. Missing/invalid markers fail closed. */
object UpdateDistribution {
    fun official(input: InputStream?, version: String): Boolean {
        if (input == null) return false
        return try {
            input.use {
                val bytes = it.readNBytes(1025)
                if (bytes.size > 1024) return false
                val props = Properties().apply { bytes.inputStream().use { load(it) } }
                props.getProperty("officialRelease") == "true" &&
                    props.getProperty("version") == version &&
                    ReleaseVersion.parse(version) != null
            }
        } catch (_: Exception) {
            false
        }
    }
}
