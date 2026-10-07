package dev.mithril.mithrilpf.update

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.Flow
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.Test

class UpdateTest {
    private fun release(version: String, draft: Boolean = false): JsonObject =
        JsonObject().apply {
            addProperty("tag_name", "v$version")
            addProperty("draft", draft)
            addProperty("prerelease", version.contains("-"))
            addProperty("published_at", "2026-09-26T00:00:00Z")
            add(
                "assets",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("name", "mithrilpf-$version.jar.sig")
                            addProperty("state", "uploaded")
                            addProperty("size", 64)
                            addProperty(
                                "browser_download_url",
                                "${UpdateCatalog.REPO}/releases/download/v$version/mithrilpf-$version.jar.sig",
                            )
                        }
                    )
                    add(
                        JsonObject().apply {
                            addProperty("name", "mithrilpf-$version.jar")
                            addProperty("state", "uploaded")
                            addProperty("size", 1024)
                            addProperty("digest", "sha256:" + "a".repeat(64))
                            addProperty(
                                "browser_download_url",
                                "${UpdateCatalog.REPO}/releases/download/v$version/mithrilpf-$version.jar",
                            )
                        }
                    )
                },
            )
        }

    @Test
    fun versionOrderingAndValidation() {
        val names =
            listOf(
                "0.2.0-alpha.1",
                "0.2.0-beta.1",
                "0.2.0-rc.2",
                "0.2.0-rc.10",
                "0.2.0",
                "0.2.1",
                "0.10.0",
                "1.0.0",
            )
        assertEquals(
            names,
            names.reversed().map { ReleaseVersion.parse(it)!! }.sorted().map { it.text },
        )
        for (bad in
            listOf(
                "01.2.0",
                "0.2",
                "0.2.0-rc.0",
                "0.2.0+dev",
                "../0.2.0",
                "999999999999.0.0",
            )) assertNull(ReleaseVersion.parse(bad))
    }

    @Test
    fun channelsDraftsAndNoDowngrades() {
        val list =
            JsonArray()
                .apply {
                    add(release("0.3.0", true))
                    add(release("0.2.1-beta.1"))
                    add(release("0.2.0"))
                    add(release("0.2.0-rc.4"))
                    add(release("0.1.0"))
                }
                .toString()
        assertEquals(
            listOf("0.2.0"),
            UpdateCatalog.parse(list, "0.2.0-rc.3", false).map { it.version.text },
        )
        assertEquals(
            listOf("0.2.1-beta.1", "0.2.0", "0.2.0-rc.4"),
            UpdateCatalog.parse(list, "0.2.0-rc.3", true).map { it.version.text },
        )
        assertTrue(UpdateCatalog.parse(list, "0.4.0-beta.1", false).isEmpty())
        assertTrue(UpdateCatalog.parse("[]", "0.2.0", false).isEmpty())
    }

    @Test
    fun rejectsUntrustedOrAmbiguousAssets() {
        for (key in listOf("browser_download_url", "digest", "state", "size")) {
            val data = release("1.0.0")
            data["assets"].asJsonArray[1].asJsonObject.addProperty(key, "invalid")
            assertTrue(UpdateCatalog.parse(data.toString(), "0.2.0", false).isEmpty())
        }
        val mismatch = release("1.0.0")
        mismatch.addProperty("prerelease", true)
        assertTrue(UpdateCatalog.parse(mismatch.toString(), "0.2.0", true).isEmpty())
        val duplicate = release("1.0.0")
        duplicate["assets"].asJsonArray.add(duplicate["assets"].asJsonArray[0].deepCopy())
        assertTrue(UpdateCatalog.parse(duplicate.toString(), "0.2.0", true).isEmpty())
        assertFails {
            UpdateCatalog.parse(
                JsonArray().apply { repeat(51) { add(release("1.0.0")) } }.toString(),
                "0.2.0",
                true,
            )
        }
    }

    @Test
    fun sizeBoundaries() {
        for (size in listOf(0, 1, UpdateCatalog.MAX_JAR, UpdateCatalog.MAX_JAR + 1)) {
            val data = release("1.0.0")
            data["assets"].asJsonArray[1].asJsonObject.addProperty("size", size)
            assertEquals(
                size in 1..UpdateCatalog.MAX_JAR,
                UpdateCatalog.parse(data.toString(), "0.2.0", false).isNotEmpty(),
            )
        }
    }

    @Test
    fun rejectsUnsignedAndInvalidSignatureAssets() {
        for (key in listOf("state", "size", "browser_download_url", "name")) {
            val data = release("1.0.0")
            data["assets"].asJsonArray[0].asJsonObject.addProperty(key, "invalid")
            assertTrue(UpdateCatalog.parse(data.toString(), "0.2.0", false).isEmpty())
        }
        val data = release("1.0.0")
        data["assets"].asJsonArray.remove(0)
        assertTrue(UpdateCatalog.parse(data.toString(), "0.2.0", false).isEmpty())
        val duplicate = release("1.0.0")
        duplicate["assets"].asJsonArray.add(duplicate["assets"].asJsonArray[1].deepCopy())
        assertTrue(UpdateCatalog.parse(duplicate.toString(), "0.2.0", false).isEmpty())
    }

    @Test
    fun restrictsNetworkDestinations() {
        for (url in
            listOf(
                UpdateCatalog.API,
                UpdateCatalog.API + "/latest",
                "${UpdateCatalog.REPO}/releases/download/v1.0.0/mithrilpf-1.0.0.jar",
                "https://release-assets.githubusercontent.com/a?signature=x",
                "https://api.modrinth.com/v2/project/1A2mKfBx/version?loaders=x",
                "https://cdn.modrinth.com/data/1A2mKfBx/versions/abc/mod.jar",
            )) assertTrue(UpdateHttp.allowed(URI(url)))
        for (url in
            listOf(
                "http://github.com/MithrilAddons/mithrilpf/releases/download/a",
                "https://github.com.evil.test/a",
                "https://github.com/other/repo/releases/download/a",
                "https://user@release-assets.githubusercontent.com/a",
                "https://127.0.0.1/a",
                "https://api.github.com/user",
                "https://release-assets.githubusercontent.com:8443/a",
                "https://api.modrinth.com/v2/user",
                "https://api.modrinth.com/v2/project/1A2mKfBx/version/../../user",
                "https://cdn.modrinth.com/other/file.jar",
                "https://cdn.modrinth.com/data/../other/file.jar",
                "http://cdn.modrinth.com/data/1A2mKfBx/mod.jar",
            )) assertFalse(UpdateHttp.allowed(URI(url)))
    }

    @Test
    fun boundedSubscriberCancelsBeforeOversizedAllocation() {
        var cancelled = false
        val subscriber = BoundedBytes(3)
        subscriber.onSubscribe(
            object : Flow.Subscription {
                override fun request(n: Long) {}

                override fun cancel() {
                    cancelled = true
                }
            }
        )
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2))))
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(3, 4))))
        assertTrue(cancelled)
        assertFails { subscriber.body.toCompletableFuture().get() }
        val exact = BoundedBytes(3)
        exact.onSubscribe(
            object : Flow.Subscription {
                override fun request(n: Long) {}

                override fun cancel() {
                    error("Unexpected cancellation")
                }
            }
        )
        exact.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
        exact.onComplete()
        assertContentEquals(byteArrayOf(1, 2, 3), exact.body.toCompletableFuture().get())
    }

    @Test
    fun settingsRoundTripPreservesUnknownFieldsAndRefusesBadFiles() {
        val directory = Files.createTempDirectory("update-settings-test")
        try {
            val file = directory.resolve("updates.json")
            val store = UpdateSettingsStore(file)
            assertEquals(UpdateSettings(true, false), store.load())
            Files.writeString(file, """{"version":1,"future":"keep"}""")
            store.save(UpdateSettings(false, true))
            assertEquals(UpdateSettings(false, true), store.load())
            assertTrue(Files.readString(file).contains("keep"))
            store.save(UpdateSettings(true, true, skipped = "0.3.0-rc.1", asked = false))
            assertEquals(UpdateSettings(true, true, "0.3.0-rc.1", false), store.load())
            store.save(UpdateSettings(true, true))
            assertFalse(Files.readString(file).contains("skipped"))
            for (bad in
                listOf(
                    "{",
                    """{"version":2}""",
                    """{"version":1,"enabled":"yes"}""",
                    """{"version":1,"skipped":"latest"}""",
                )) {
                Files.writeString(file, bad)
                assertFails { store.save(UpdateSettings()) }
                assertEquals(bad, Files.readString(file))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun validatesJarIdentityHashAndInstalledDependencies() {
        val dir = Files.createTempDirectory("update-jar-test")
        try {
            val jar = dir.resolve("test.jar")
            fun write(id: String = "mithrilpf", mc: String = "26.1.2", breaks: String = "") {
                ZipOutputStream(Files.newOutputStream(jar)).use {
                    it.putNextEntry(ZipEntry("fabric.mod.json"))
                    it.write(
                        """{"id":"$id","version":"1.0.0","environment":"client","depends":{"minecraft":"$mc","java":">=25","fabricloader":">=0.19.3","fabric-api":">=1.0.0"}$breaks}"""
                            .toByteArray()
                    )
                    it.closeEntry()
                    it.putNextEntry(ZipEntry("assets/mithrilpf/build.properties"))
                    it.write("version=1.0.0\nofficialRelease=true\n".toByteArray())
                    it.closeEntry()
                }
            }
            fun artifact() =
                UpdateRelease(
                    ReleaseVersion.parse("1.0.0")!!,
                    URI("https://example.invalid"),
                    Files.size(jar),
                    UpdateArtifact.hash(jar),
                )
            val mods =
                mapOf(
                    "minecraft" to "26.1.2",
                    "java" to "25",
                    "fabricloader" to "0.19.3",
                    "fabric-api" to "1.0.0",
                )
            write()
            fun unmet(installed: Map<String, String>) =
                UpdateArtifact.unmet(jar, artifact(), installed).map { it.toString() }
            assertEquals(emptyList(), unmet(mods))
            assertEquals(
                listOf("fabric-api >=1.0.0"),
                unmet(mods - "fabric-api"),
            )
            assertEquals(
                listOf("java >=25"),
                unmet(mods + ("java" to "21")),
            )
            val original = artifact()
            Files.write(jar, byteArrayOf(1))
            assertFails { UpdateArtifact.unmet(jar, original, mods) }
            write(mc = "27.0")
            assertEquals(listOf("minecraft 27.0"), unmet(mods))
            write(id = "other")
            assertFails { UpdateArtifact.unmet(jar, artifact(), mods) }
            write(breaks = ""","breaks":{"fabric-api":"*"}""")
            assertEquals(listOf("no fabric-api"), unmet(mods))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
