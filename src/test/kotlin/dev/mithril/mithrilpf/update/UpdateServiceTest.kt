package dev.mithril.mithrilpf.update

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.Test

class UpdateServiceTest {
    private class Harness(
        saved: Boolean = true,
        extraDepends: String = "",
        sources: Boolean = false,
    ) : AutoCloseable {
        val root = Files.createTempDirectory("updater-service-test").toRealPath()
        val queue = LinkedBlockingQueue<() -> Unit>()
        val installed =
            Files.writeString(
                Files.createDirectory(root.resolve("mods")).resolve("mithrilpf.jar"),
                "synthetic old mod",
            )
        val artifact = root.resolve("fixture.jar")
        val dependency = root.resolve("dependency.jar")
        var launched: PreparedUpdate? = null
        var service: UpdateService? = null

        init {
            ZipOutputStream(Files.newOutputStream(artifact)).use {
                it.putNextEntry(ZipEntry("fabric.mod.json"))
                it.write(
                    """{"id":"mithrilpf","version":"0.3.0-beta.1","environment":"client","depends":{"minecraft":"26.1.2","java":">=25","fabricloader":">=0.19.3"$extraDepends}}"""
                        .toByteArray()
                )
                it.closeEntry()
                it.putNextEntry(ZipEntry("assets/mithrilpf/build.properties"))
                it.write("version=0.3.0-beta.1\nofficialRelease=true\n".toByteArray())
                it.closeEntry()
                if (sources) {
                    it.putNextEntry(ZipEntry("assets/mithrilpf/dependencies.json"))
                    it.write(
                        """{"version":1,"modrinth":{"hypixel-mod-api":{"project":"1A2mKfBx","name":"Hypixel Mod API"}}}"""
                            .toByteArray()
                    )
                    it.closeEntry()
                }
            }
            ZipOutputStream(Files.newOutputStream(dependency)).use {
                it.putNextEntry(ZipEntry("fabric.mod.json"))
                it.write(
                    """{"id":"hypixel-mod-api","version":"1.0.2","environment":"client","depends":{"minecraft":">=26.1"}}"""
                        .toByteArray()
                )
                it.closeEntry()
            }
            if (saved)
                UpdateSettingsStore(root.resolve("config/mithrilpf/updates.json"))
                    .save(UpdateSettings(true, true))
        }

        var requests = 0
        var jarRequests = 0
        var dependencyRequests = 0

        fun start(
            official: Boolean = true,
            firstRun: Boolean = false,
            invalidSignature: Boolean = false,
            tamperedJar: Boolean = false,
            tamperedDependency: Boolean = false,
            extraInstalled: Map<String, String> = emptyMap(),
            block: (() -> Unit)? = null,
        ): UpdateService {
            val bytes = Files.readAllBytes(artifact)
            val hash = UpdateArtifact.hash(artifact)
            val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val url =
                "${UpdateCatalog.REPO}/releases/download/v0.3.0-beta.1/mithrilpf-0.3.0-beta.1.jar"
            val signature =
                Signature.getInstance("Ed25519").run {
                    initSign(key.private)
                    update(
                        UpdateSignature.payload(
                            UpdateRelease(
                                ReleaseVersion.parse("0.3.0-beta.1")!!,
                                URI(url),
                                bytes.size.toLong(),
                                hash,
                            )
                        )
                    )
                    sign().also { if (invalidSignature) it[0] = (it[0].toInt() xor 1).toByte() }
                }
            val release =
                """[{"tag_name":"v0.3.0-beta.1","draft":false,"prerelease":true,"published_at":"2026-09-26","body":"Notes **Changes**","assets":[{"name":"mithrilpf-0.3.0-beta.1.jar","state":"uploaded","size":${bytes.size},"digest":"sha256:$hash","browser_download_url":"$url"},{"name":"mithrilpf-0.3.0-beta.1.jar.sig","state":"uploaded","size":64,"browser_download_url":"$url.sig"}]}]"""
            val dependencyBytes = Files.readAllBytes(dependency)
            val sha512 =
                java.security.MessageDigest.getInstance("SHA-512")
                    .digest(dependencyBytes)
                    .joinToString("") {
                        "%02x".format(it)
                    }
            val versions =
                """[{"project_id":"1A2mKfBx","version_type":"beta","version_number":"1.0.3","loaders":["fabric"],"game_versions":["26.1.2"],"date_published":"2026-10-02","files":[{"primary":true,"filename":"hypixel-mod-api-1.0.3.jar","url":"https://cdn.modrinth.com/data/1A2mKfBx/versions/b/hypixel-mod-api-1.0.3.jar","size":${dependencyBytes.size},"hashes":{"sha512":"$sha512"}}]},{"project_id":"1A2mKfBx","version_type":"release","version_number":"1.0.2","loaders":["fabric"],"game_versions":["26.1.2"],"date_published":"2026-03-24","files":[{"primary":true,"filename":"hypixel-mod-api-1.0.2.jar","url":"https://cdn.modrinth.com/data/1A2mKfBx/versions/a/hypixel-mod-api-1.0.2.jar","size":${dependencyBytes.size},"hashes":{"sha512":"$sha512"}}]}]"""
            return UpdateService(
                    UpdateEnvironment(
                        root,
                        "0.2.0",
                        mapOf("minecraft" to "26.1.2", "java" to "25", "fabricloader" to "0.19.3") +
                            extraInstalled,
                        installed,
                        { ByteArrayInputStream(byteArrayOf(1, 2)) },
                        official,
                        key.public.encoded,
                        firstRun,
                    ),
                    { queue.add(it) },
                    { uri, _ ->
                        requests++
                        when {
                            uri.path.endsWith("/latest") -> "[]".toByteArray()
                            uri.host == "api.github.com" -> release.toByteArray()
                            uri.path.endsWith(".sig") -> signature
                            uri.host == "api.modrinth.com" -> versions.toByteArray()
                            uri.host == "cdn.modrinth.com" -> {
                                dependencyRequests++
                                dependencyBytes.copyOf().also {
                                    if (tamperedDependency) it[0] = (it[0].toInt() xor 1).toByte()
                                }
                            }
                            else -> {
                                jarRequests++
                                block?.invoke()
                                bytes.copyOf().also {
                                    if (tamperedJar) it[0] = (it[0].toInt() xor 1).toByte()
                                }
                            }
                        }
                    },
                    { launched = it },
                )
                .also { service = it }
        }

        fun await(state: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (service!!.status.state != state && System.nanoTime() < deadline) queue
                .poll(50, TimeUnit.MILLISECONDS)
                ?.invoke()
            assertEquals(state, service!!.status.state)
        }

        override fun close() {
            service?.close()
            // Tests wait for workers before deleting their temporary fixtures.
            assertTrue(service?.awaitStopped() ?: true)
            root.toFile().deleteRecursively()
        }
    }

    private fun Harness.staged(): Long =
        Files.list(root.resolve("config/mithrilpf/updates")).use { it.count() }

    @Test
    fun checkingNeverDownloadsOrInstallsWithoutApproval() =
        Harness().use { h ->
            val service = h.start()
            h.await("available")
            assertEquals("0.3.0-beta.1", service.status.version)
            assertEquals("Notes **Changes**", service.status.release?.notes)
            assertEquals(0, h.jarRequests)
            service.close()
            assertNull(h.launched)
            assertEquals("synthetic old mod", Files.readString(h.installed))
        }

    @Test
    fun approvedUpdateStagesAndInstallsOnlyOnClose() =
        Harness().use { h ->
            val service = h.start()
            h.await("available")
            service.install()
            h.await("ready")
            assertNull(h.launched)
            assertEquals("synthetic old mod", Files.readString(h.installed))
            service.close()
            assertEquals("0.3.0-beta.1", h.launched!!.version.text)
        }

    @Test
    fun invalidSignatureNeverDownloadsOrStagesJar() =
        Harness().use { h ->
            val service = h.start(invalidSignature = true)
            h.await("available")
            service.install()
            h.await("failed")
            service.close()
            assertNull(h.launched)
            assertEquals(0, h.jarRequests)
            assertEquals("synthetic old mod", Files.readString(h.installed))
            assertEquals(0, h.staged())
        }

    @Test
    fun signedMetadataCannotAuthorizeTamperedJar() =
        Harness().use { h ->
            val service = h.start(tamperedJar = true)
            h.await("available")
            service.install()
            h.await("failed")
            service.close()
            assertNull(h.launched)
            assertEquals(1, h.jarRequests)
            assertEquals("synthetic old mod", Files.readString(h.installed))
            assertEquals(0, h.staged())
        }

    @Test
    fun localBuildNeverContactsRemoteOrLaunchesInstaller() =
        Harness().use { h ->
            val service = h.start(official = false)
            h.await("local")
            service.configure(UpdateSettings(true, true))
            h.await("local")
            service.install()
            service.close()
            assertEquals(0, h.requests)
            assertNull(h.launched)
            assertEquals("synthetic old mod", Files.readString(h.installed))
        }

    @Test
    fun cancellingWithdrawsApproval() =
        Harness().use { h ->
            val service = h.start()
            h.await("available")
            service.install()
            h.await("ready")
            service.cancel()
            assertEquals("available", service.status.state)
            service.close()
            assertNull(h.launched)
        }

    @Test
    fun skippedVersionIsNotOfferedButCanStillBeInstalled() =
        Harness().use { h ->
            val service = h.start()
            h.await("available")
            service.skip()
            h.await("skipped")
            assertEquals(
                "0.3.0-beta.1",
                UpdateSettingsStore(h.root.resolve("config/mithrilpf/updates.json")).load().skipped,
            )
            service.install()
            h.await("ready")
            service.close()
            assertTrue(h.launched != null)
        }

    @Test
    fun newInstallsAskBeforeCheckingAndExistingInstallsDoNot() {
        Harness(saved = false).use { h ->
            val service = h.start(firstRun = true)
            h.await("ask")
            assertEquals(0, h.requests)
            service.configure(UpdateSettings(enabled = true, prereleases = true, asked = true))
            h.await("available")
        }
        Harness(saved = false).use { h ->
            h.start(firstRun = false)
            h.await("current")
        }
    }

    @Test
    fun missingDependencyIsNamedAndNothingIsStaged() =
        Harness(extraDepends = ""","hypixel-mod-api":">=1.0.2"""").use { h ->
            val service = h.start()
            h.await("available")
            service.install()
            h.await("incompatible")
            assertEquals(listOf("hypixel-mod-api >=1.0.2"), service.status.unmet)
            service.close()
            assertNull(h.launched)
            assertEquals(0, h.staged())
        }

    @Test
    fun disablingOrChangingChannelCancelsStagedInstall() {
        for (settings in listOf(UpdateSettings(false, true), UpdateSettings(true, false))) {
            Harness().use { h ->
                val service = h.start()
                h.await("available")
                service.install()
                h.await("ready")
                service.configure(settings)
                h.await(if (settings.enabled) "current" else "disabled")
                service.close()
                assertNull(h.launched)
                assertEquals(
                    settings,
                    UpdateSettingsStore(h.root.resolve("config/mithrilpf/updates.json")).load(),
                )
            }
        }
    }

    @Test
    fun lateDownloadCannotRestoreCancelledUpdate() =
        Harness().use { h ->
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val service = h.start {
                started.countDown()
                // Simulate a transport completing despite cancellation.
                try {
                    finish.await(3, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {}
            }
            h.await("available")
            service.install()
            assertTrue(started.await(3, TimeUnit.SECONDS))
            service.configure(UpdateSettings(false, false))
            finish.countDown()
            h.await("disabled")
            service.close()
            assertNull(h.launched)
        }

    private val dependsOnModApi = ""","hypixel-mod-api":">=1.0.2""""

    @Test
    fun missingDependencyIsOfferedAndInstalledOnlyAfterApproval() =
        Harness(extraDepends = dependsOnModApi, sources = true).use { h ->
            val service = h.start()
            h.await("available")
            service.install()
            h.await("dependencies")
            assertEquals(listOf("Hypixel Mod API 1.0.2"), service.status.unmet)
            assertEquals(0, h.dependencyRequests)
            assertFalse(Files.exists(h.root.resolve("mods/hypixel-mod-api-1.0.2.jar")))
            service.installWithDependencies()
            h.await("ready")
            assertEquals(1, h.dependencyRequests)
            assertTrue(Files.exists(h.root.resolve("mods/hypixel-mod-api-1.0.2.jar")))
            service.close()
            assertEquals("0.3.0-beta.1", h.launched!!.version.text)
        }

    @Test
    fun tamperedDependencyIsNeverAddedToMods() =
        Harness(extraDepends = dependsOnModApi, sources = true).use { h ->
            val service = h.start(tamperedDependency = true)
            h.await("available")
            service.install()
            h.await("dependencies")
            service.installWithDependencies()
            h.await("failed")
            service.close()
            assertNull(h.launched)
            Files.list(h.root.resolve("mods")).use { assertEquals(1, it.count()) }
            assertEquals(0, h.staged())
        }

    @Test
    fun existingFilesAreNeverReplaced() =
        Harness(extraDepends = dependsOnModApi, sources = true).use { h ->
            val existing =
                Files.writeString(h.root.resolve("mods/hypixel-mod-api-1.0.2.jar"), "mine")
            val service = h.start()
            h.await("available")
            service.install()
            h.await("dependencies")
            service.installWithDependencies()
            h.await("failed")
            service.close()
            assertEquals("mine", Files.readString(existing))
            assertNull(h.launched)
        }

    @Test
    fun outdatedOrUnlistedDependenciesAreOnlyNamed() {
        Harness(extraDepends = dependsOnModApi, sources = true).use { h ->
            val service = h.start(extraInstalled = mapOf("hypixel-mod-api" to "1.0.0"))
            h.await("available")
            service.install()
            h.await("incompatible")
            assertEquals(listOf("hypixel-mod-api >=1.0.2"), service.status.unmet)
        }
        Harness(extraDepends = dependsOnModApi).use { h ->
            val service = h.start()
            h.await("available")
            service.install()
            h.await("incompatible")
            assertEquals(0, h.dependencyRequests)
        }
    }
}
