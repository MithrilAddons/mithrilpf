package dev.mithril.mithrilpf.update

import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

data class UpdateStatus(
    val settings: UpdateSettings = UpdateSettings(),
    val loaded: Boolean = false,
    val state: String = "loading",
    val version: String = "",
)

internal data class UpdateEnvironment(
    val game: Path,
    val current: String,
    val installed: Map<String, String>,
    val origin: Path?,
    val helper: () -> InputStream?,
)

/** Client-thread state; one worker owns files/network. Tests inject delivery and transport. */
internal class UpdateService(
    private val environment: UpdateEnvironment,
    private val post: (() -> Unit) -> Unit,
    private val fetch: (URI, Int) -> ByteArray,
    private val launch: (PreparedUpdate) -> Unit,
) : AutoCloseable {
    private val current = environment.current
    private val game = environment.game
    private val origin = environment.origin
    private val installed = environment.installed
    private val store = UpdateSettingsStore(game.resolve("config/mithrilpf/updates.json"))
    private val log = LoggerFactory.getLogger("MithrilPF Updates")
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MithrilPF updates").apply { isDaemon = true }
    }
    @Volatile private var generation = 0
    private var task: Future<*>? = null
    private var pending: PreparedUpdate? = null
    private var lastCheck = Long.MIN_VALUE
    private var closed = false
    var status = UpdateStatus()
        private set

    init {
        submit(null)
    }

    fun configure(settings: UpdateSettings) {
        if (!status.loaded || closed) return
        status = UpdateStatus(settings, false, "saving")
        submit(settings)
    }

    fun checkNow() {
        if (
            closed ||
                !status.loaded ||
                !status.settings.enabled ||
                status.state in setOf("checking", "downloading", "ready")
        )
            return
        val now = System.nanoTime()
        if (lastCheck != Long.MIN_VALUE && now - lastCheck < TimeUnit.MINUTES.toNanos(1)) return
        submit(null)
    }

    private fun submit(save: UpdateSettings?) {
        val token = ++generation
        task?.cancel(true)
        val discarded = pending
        pending = null
        lastCheck = System.nanoTime()
        status = status.copy(state = if (save == null) "checking" else "saving")
        task = worker.submit {
            var loaded = false
            try {
                if (discarded != null) {
                    Files.deleteIfExists(discarded.staged)
                    Files.deleteIfExists(discarded.helper)
                    Files.deleteIfExists(discarded.staged.parent)
                }
                if (save != null) store.save(save)
                val settings = store.load()
                loaded = true
                publish(token) { status = UpdateStatus(settings, true, "checking") }
                if (!settings.enabled) {
                    publish(token) { status = UpdateStatus(settings, true, "disabled") }
                    return@submit
                }
                val target = origin?.toAbsolutePath()?.normalize()
                if (
                    target == null ||
                        target.parent != game.resolve("mods") ||
                        !Files.isRegularFile(target) ||
                        Files.isSymbolicLink(target) ||
                        target.toRealPath() != target ||
                        !target.fileName.toString().endsWith(".jar")
                ) {
                    publish(token) { status = UpdateStatus(settings, true, "unsupported") }
                    return@submit
                }
                val uri =
                    java.net.URI(
                        UpdateCatalog.API + if (settings.prereleases) "?per_page=50" else "/latest"
                    )
                val releases =
                    UpdateCatalog.parse(
                        fetch(uri, 1024 * 1024).toString(Charsets.UTF_8),
                        current,
                        settings.prereleases,
                    )
                if (releases.isEmpty()) {
                    publish(token) { status = UpdateStatus(settings, true, "current") }
                    return@submit
                }
                val oldHash = UpdateArtifact.hash(target)
                for (release in releases) {
                    if (token != generation || Thread.currentThread().isInterrupted) return@submit
                    publish(token) {
                        status = UpdateStatus(settings, true, "downloading", release.version.text)
                    }
                    val directory = game.resolve("config/mithrilpf/updates")
                    Files.createDirectories(directory)
                    val job = Files.createTempDirectory(directory, "job-")
                    val staged = job.resolve("update.jar")
                    var retain = false
                    try {
                        val bytes = fetch(release.uri, UpdateCatalog.MAX_JAR)
                        require(bytes.size.toLong() == release.size)
                        Files.write(staged, bytes)
                        if (!UpdateArtifact.compatible(staged, release, installed)) continue
                        val helper = job.resolve("updater.jar")
                        requireNotNull(environment.helper()).use {
                            Files.copy(it, helper)
                        }
                        if (token != generation || Thread.currentThread().isInterrupted)
                            return@submit
                        val prepared =
                            PreparedUpdate(
                                target,
                                staged,
                                helper,
                                oldHash,
                                release.sha256,
                                release.version,
                            )
                        publish(token) {
                            pending = prepared
                            status = UpdateStatus(settings, true, "ready", release.version.text)
                        }
                        retain = true
                        return@submit
                    } finally {
                        if (!retain) {
                            Files.deleteIfExists(staged)
                            Files.deleteIfExists(job.resolve("updater.jar"))
                            Files.deleteIfExists(job)
                        }
                    }
                }
                publish(token) { status = UpdateStatus(settings, true, "incompatible") }
            } catch (e: Exception) {
                if (token == generation) {
                    log.warn("Update check failed ({})", e.javaClass.simpleName)
                    publish(token) {
                        status =
                            status.copy(
                                loaded = loaded,
                                state = if (loaded) "failed" else "storage_error",
                            )
                    }
                }
            }
        }
    }

    private fun publish(token: Int, change: () -> Unit) {
        post { if (!closed && generation == token) change() }
    }

    internal fun awaitStopped(): Boolean = worker.awaitTermination(5, TimeUnit.SECONDS)

    override fun close() {
        if (closed) return
        closed = true
        ++generation
        task?.cancel(true)
        worker.shutdownNow()
        val update = pending ?: return
        if (!status.settings.enabled || (update.version.prerelease && !status.settings.prereleases))
            return
        try {
            launch(update)
        } catch (e: Exception) {
            log.warn("Could not start update installer ({})", e.javaClass.simpleName)
        }
    }
}

internal data class PreparedUpdate(
    val target: Path,
    val staged: Path,
    val helper: Path,
    val oldHash: String,
    val newHash: String,
    val version: ReleaseVersion,
)
