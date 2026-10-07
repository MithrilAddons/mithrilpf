package dev.mithril.mithrilpf.update

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ModrinthDependenciesTest {
    private val source = DependencySource("hypixel-mod-api", "1A2mKfBx", "Hypixel Mod API")
    private val requirement = Requirement("hypixel-mod-api", listOf(">=1.0.2"))

    private fun version(
        number: String = "1.0.2",
        project: String = "1A2mKfBx",
        type: String = "release",
        game: String = "26.1.2",
        url: String = "https://cdn.modrinth.com/data/1A2mKfBx/versions/a/mod.jar",
        published: String = "2026-03-24",
    ) =
        """{"project_id":"$project","version_type":"$type","version_number":"$number","loaders":["fabric"],"game_versions":["$game"],"date_published":"$published","files":[{"primary":true,"filename":"mod.jar","url":"$url","size":10,"hashes":{"sha512":"${"a".repeat(128)}"}}]}"""

    private fun pick(vararg versions: String) =
        ModrinthDependencies.pick("[${versions.joinToString(",")}]", source, requirement, "26.1.2")

    @Test
    fun `only matching Fabric releases from the named project and CDN are offered`() {
        assertEquals("1.0.2", pick(version())?.version)
        assertEquals(
            "1.0.4",
            pick(
                    version("1.0.3", published = "2026-04-01"),
                    version("1.0.4", published = "2026-05-01"),
                )
                ?.version,
        )
        for (rejected in
            listOf(
                version(type = "beta"),
                version(project = "AAAAAAAA"),
                version(game = "26.2"),
                version(number = "1.0.1"),
                version(url = "https://cdn.modrinth.com/data/AAAAAAAA/versions/a/mod.jar"),
                version(url = "https://evil.example/data/1A2mKfBx/mod.jar"),
                version(url = "http://cdn.modrinth.com/data/1A2mKfBx/versions/a/mod.jar"),
            )) assertNull(pick(rejected), rejected)
    }

    @Test
    fun `dependency sources come only from a valid list inside the release`() {
        val dir = Files.createTempDirectory("dependency-sources")
        try {
            val jar = dir.resolve("release.jar")
            fun write(json: String?) =
                ZipOutputStream(Files.newOutputStream(jar)).use {
                    it.putNextEntry(ZipEntry("fabric.mod.json"))
                    it.closeEntry()
                    if (json != null) {
                        it.putNextEntry(ZipEntry("assets/mithrilpf/dependencies.json"))
                        it.write(json.toByteArray())
                        it.closeEntry()
                    }
                }
            write(null)
            assertTrue(ModrinthDependencies.sources(jar).isEmpty())
            write(
                """{"version":1,"modrinth":{"hypixel-mod-api":{"project":"1A2mKfBx","name":"Hypixel Mod API"}}}"""
            )
            assertEquals(source, ModrinthDependencies.sources(jar)["hypixel-mod-api"])
            for (bad in
                listOf(
                    """{"version":2,"modrinth":{}}""",
                    """{"version":1,"modrinth":{"x":{"project":"bad","name":"X"}}}""",
                    """{"version":1,"modrinth":{"Bad Id":{"project":"1A2mKfBx","name":"X"}}}""",
                )) {
                write(bad)
                assertFails { ModrinthDependencies.sources(jar) }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `every required mod in this build has a Modrinth source`() {
        val metadata =
            com.google.gson.JsonParser.parseString(
                    checkNotNull(javaClass.getResource("/fabric.mod.json")).readText()
                )
                .asJsonObject
        val listed =
            com.google.gson.JsonParser.parseString(
                    checkNotNull(javaClass.getResource("/assets/mithrilpf/dependencies.json"))
                        .readText()
                )
                .asJsonObject["modrinth"]
                .asJsonObject
                .keySet()
        assertEquals(
            metadata["depends"].asJsonObject.keySet() - setOf("minecraft", "java", "fabricloader"),
            listed,
        )
    }
}
