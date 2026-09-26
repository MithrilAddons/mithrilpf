package dev.mithril.mithrilpf.update

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.Test

class UpdateServiceTest {
    private class Harness : AutoCloseable {
        val root = Files.createTempDirectory("updater-service-test").toRealPath()
        val queue = LinkedBlockingQueue<() -> Unit>()
        val installed =
            Files.writeString(
                Files.createDirectory(root.resolve("mods")).resolve("mithrilpf.jar"),
                "synthetic old mod",
            )
        val artifact = root.resolve("fixture.jar")
        var launched: PreparedUpdate? = null
        var service: UpdateService? = null

        init {
            ZipOutputStream(Files.newOutputStream(artifact)).use {
                it.putNextEntry(ZipEntry("fabric.mod.json"))
                it.write(
                    """{"id":"mithrilpf","version":"0.3.0-beta.1","environment":"client","depends":{"minecraft":"26.1.2","java":">=25","fabricloader":">=0.19.3"}}"""
                        .toByteArray()
                )
                it.closeEntry()
                it.putNextEntry(ZipEntry("assets/mithrilpf/build.properties"))
                it.write("version=0.3.0-beta.1\nofficialRelease=true\n".toByteArray())
                it.closeEntry()
            }
            UpdateSettingsStore(root.resolve("config/mithrilpf/updates.json"))
                .save(UpdateSettings(true, true))
        }

        var requests = 0

        fun start(official: Boolean = true, block: (() -> Unit)? = null): UpdateService {
            val bytes = Files.readAllBytes(artifact)
            val hash = UpdateArtifact.hash(artifact)
            val release =
                """[{"tag_name":"v0.3.0-beta.1","draft":false,"prerelease":true,"published_at":"2026-09-26","assets":[{"name":"mithrilpf-0.3.0-beta.1.jar","state":"uploaded","size":${bytes.size},"digest":"sha256:$hash","browser_download_url":"${UpdateCatalog.REPO}/releases/download/v0.3.0-beta.1/mithrilpf-0.3.0-beta.1.jar"}]}]"""
            return UpdateService(
                    UpdateEnvironment(
                        root,
                        "0.2.0",
                        mapOf("minecraft" to "26.1.2", "java" to "25", "fabricloader" to "0.19.3"),
                        installed,
                        { ByteArrayInputStream(byteArrayOf(1, 2)) },
                        official,
                    ),
                    { queue.add(it) },
                    { uri, _ ->
                        requests++
                        when {
                            uri.path.endsWith("/latest") -> "[]".toByteArray()
                            uri.host == "api.github.com" -> release.toByteArray()
                            else -> {
                                block?.invoke()
                                bytes
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

    @Test
    fun localBuildNeverContactsRemoteOrLaunchesInstaller() =
        Harness().use { h ->
            val service = h.start(official = false)
            h.await("local")
            service.configure(UpdateSettings(true, true))
            h.await("local")
            service.close()
            assertEquals(0, h.requests)
            assertNull(h.launched)
            assertEquals("synthetic old mod", Files.readString(h.installed))
        }

    @Test
    fun stagesButDoesNotInstallUntilClose() =
        Harness().use { h ->
            val service = h.start()
            h.await("ready")
            assertNull(h.launched)
            assertEquals("synthetic old mod", Files.readString(h.installed))
            service.close()
            assertNotNull(h.launched)
            assertEquals("0.3.0-beta.1", h.launched!!.version.text)
        }

    @Test
    fun disablingOrChangingChannelCancelsStagedInstall() {
        for (settings in listOf(UpdateSettings(false, true), UpdateSettings(true, false))) {
            Harness().use { h ->
                val service = h.start()
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
            h.await("downloading")
            assertTrue(started.await(3, TimeUnit.SECONDS))
            service.configure(UpdateSettings(false, false))
            finish.countDown()
            h.await("disabled")
            service.close()
            assertNull(h.launched)
        }
}
