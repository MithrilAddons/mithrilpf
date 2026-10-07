package dev.mithril.mithrilpf.update

import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * [release] is the newest release offered (state available, skipped or later). [unmet] explains
 * incompatible; [dependencies] are the missing mods offered from Modrinth (state dependencies).
 */
data class UpdateStatus(
    val settings: UpdateSettings = UpdateSettings(),
    val loaded: Boolean = false,
    val state: String = "loading",
    val version: String = "",
    val release: UpdateRelease? = null,
    val unmet: List<String> = emptyList(),
    val dependencies: List<DependencyOffer> = emptyList(),
)

internal data class UpdateEnvironment(
    val game: Path,
    val current: String,
    val installed: Map<String, String>,
    val origin: Path?,
    val helper: () -> InputStream?,
    val officialRelease: Boolean,
    val signingKey: ByteArray = UpdateSignature.trustedKey(),
    /** MithrilPF had no config before this launch; such installs must opt in to checks. */
    val firstRun: Boolean = false,
)

/**
 * Client-thread state; one worker owns files/network. Checks only look for a release; nothing is
 * downloaded or installed until [install] records the player's approval. Tests inject delivery and
 * transport.
 */
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

    val cooldownSeconds: Long
        get() =
            if (lastCheck == Long.MIN_VALUE) 0
            else
                ((TimeUnit.MINUTES.toNanos(1) - (System.nanoTime() - lastCheck)).coerceAtLeast(0) +
                    999_999_999L) / 1_000_000_000L

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

    /** The player approved [UpdateStatus.release]: download, verify and stage it for exit. */
    fun install() {
        if (status.state in setOf("available", "skipped", "incompatible", "failed")) start(null)
    }

    /** The player also approved installing the offered missing mods from Modrinth. */
    fun installWithDependencies() {
        if (status.state == "dependencies") start(status.dependencies)
    }

    private fun start(approved: List<DependencyOffer>?) {
        val release = status.release ?: return
        if (closed) return
        val settings = status.settings
        val token = ++generation
        task?.cancel(true)
        status = status.copy(state = "downloading", unmet = emptyList(), dependencies = emptyList())
        task = worker.submit {
            try {
                stage(token, settings, release, approved)
            } catch (e: Exception) {
                if (token == generation) {
                    log.warn("Update download failed ({})", e.javaClass.simpleName)
                    publish(token) { status = status.copy(state = "failed") }
                }
            }
        }
    }

    /** Stops offering [UpdateStatus.release]; newer releases are still offered. */
    fun skip() {
        val release = status.release ?: return
        if (status.state == "available")
            configure(status.settings.copy(skipped = release.version.text))
    }

    /** Withdraws approval; a staged update is discarded and the release is offered again. */
    fun cancel() {
        if (closed || status.state !in setOf("downloading", "ready")) return
        ++generation
        task?.cancel(true)
        discard(pending)
        pending = null
        status = status.copy(state = "available")
    }

    private fun discard(update: PreparedUpdate?) {
        if (update == null) return
        worker.submit {
            Files.deleteIfExists(update.staged)
            Files.deleteIfExists(update.helper)
            Files.deleteIfExists(update.staged.parent)
        }
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
                val settings =
                    if (!store.exists() && environment.firstRun)
                        UpdateSettings(enabled = false, asked = false)
                    else store.load()
                loaded = true
                publish(token) { status = UpdateStatus(settings, true, "checking") }
                if (!environment.officialRelease) {
                    publish(token) { status = UpdateStatus(settings, true, "local") }
                    return@submit
                }
                if (!settings.asked) {
                    publish(token) { status = UpdateStatus(settings, true, "ask") }
                    return@submit
                }
                if (!settings.enabled) {
                    publish(token) { status = UpdateStatus(settings, true, "disabled") }
                    return@submit
                }
                if (target() == null) {
                    publish(token) { status = UpdateStatus(settings, true, "unsupported") }
                    return@submit
                }
                val uri =
                    URI(UpdateCatalog.API + if (settings.prereleases) "?per_page=50" else "/latest")
                val release =
                    UpdateCatalog.parse(
                            fetch(uri, 1024 * 1024).toString(Charsets.UTF_8),
                            current,
                            settings.prereleases,
                        )
                        .firstOrNull()
                publish(token) {
                    status =
                        when {
                            release == null -> UpdateStatus(settings, true, "current")
                            release.version.text == settings.skipped ->
                                UpdateStatus(
                                    settings,
                                    true,
                                    "skipped",
                                    release.version.text,
                                    release,
                                )
                            else ->
                                UpdateStatus(
                                    settings,
                                    true,
                                    "available",
                                    release.version.text,
                                    release,
                                )
                        }
                }
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

    /** The installed JAR this mod can replace, or null for unsupported installations. */
    private fun target(): Path? {
        val target = origin?.toAbsolutePath()?.normalize() ?: return null
        return target.takeIf {
            it.parent == game.resolve("mods") &&
                Files.isRegularFile(it) &&
                !Files.isSymbolicLink(it) &&
                it.toRealPath() == it &&
                it.fileName.toString().endsWith(".jar")
        }
    }

    /**
     * Downloads and verifies [release]. Missing mods it names on Modrinth are first offered
     * ([approved] null), then installed only once the player approves exactly those offers.
     */
    private fun stage(
        token: Int,
        settings: UpdateSettings,
        release: UpdateRelease,
        approved: List<DependencyOffer>?,
    ) {
        val target = requireNotNull(target())
        val oldHash = UpdateArtifact.hash(target)
        val directory = game.resolve("config/mithrilpf/updates")
        Files.createDirectories(directory)
        val job = Files.createTempDirectory(directory, "job-")
        val staged = job.resolve("update.jar")
        var retain = false
        var outcome: (() -> Unit)? = null
        try {
            UpdateSignature.verify(
                release,
                fetch(release.signatureUri, UpdateSignature.SIZE),
                environment.signingKey,
            )
            val bytes = fetch(release.uri, UpdateCatalog.MAX_JAR)
            require(bytes.size.toLong() == release.size)
            Files.write(staged, bytes)
            var available = installed
            val unmet = UpdateArtifact.unmet(staged, release, available)
            if (unmet.isNotEmpty()) {
                val sources = ModrinthDependencies.sources(staged)
                val minecraft = installed["minecraft"]
                val offers =
                    if (
                        minecraft == null ||
                            unmet.any { it.conflict || it.id in installed || it.id !in sources }
                    )
                        null
                    else
                        approved
                            ?: unmet.map { requirement ->
                                val source = sources.getValue(requirement.id)
                                val json =
                                    fetch(
                                            ModrinthDependencies.versionsUri(
                                                source.project,
                                                minecraft,
                                            ),
                                            1024 * 1024,
                                        )
                                        .toString(Charsets.UTF_8)
                                ModrinthDependencies.pick(json, source, requirement, minecraft)
                                    ?: return@map null
                            }
                if (offers == null || offers.any { it == null }) {
                    val names = unmet.map { it.toString() }
                    outcome = { status = status.copy(state = "incompatible", unmet = names) }
                    return
                }
                val found = offers.filterNotNull()
                if (approved == null) {
                    val names = found.map { "${it.source.name} ${it.version}" }
                    outcome = {
                        status =
                            status.copy(state = "dependencies", unmet = names, dependencies = found)
                    }
                    return
                }
                require(found.map { it.source.id }.toSet() == unmet.map { it.id }.toSet())
                available = installDependencies(found, job)
                require(UpdateArtifact.unmet(staged, release, available).isEmpty())
            }
            val helper = job.resolve("updater.jar")
            requireNotNull(environment.helper()).use { Files.copy(it, helper) }
            if (token != generation || Thread.currentThread().isInterrupted) return
            val prepared =
                PreparedUpdate(target, staged, helper, oldHash, release.sha256, release.version)
            publish(token) {
                pending = prepared
                status = UpdateStatus(settings, true, "ready", release.version.text, release)
            }
            retain = true
        } finally {
            if (!retain) {
                Files.list(job).use { files -> files.forEach(Files::deleteIfExists) }
                Files.deleteIfExists(job)
            }
            // Reported only after rejected downloads are removed.
            outcome?.let { publish(token, it) }
        }
    }

    /**
     * Downloads and verifies every approved dependency before any of them is added to `mods/`.
     * Existing files are never replaced. Returns the mods available once they load.
     */
    private fun installDependencies(offers: List<DependencyOffer>, job: Path): Map<String, String> {
        val expected = installed + offers.associate { it.source.id to it.version }
        val verified = offers.mapIndexed { index, offer ->
            val file = job.resolve("dependency-$index.jar")
            val bytes = fetch(offer.uri, ModrinthDependencies.MAX_JAR)
            require(bytes.size.toLong() == offer.size)
            Files.write(file, bytes)
            Triple(
                offer,
                file,
                ModrinthDependencies.verify(file, offer, expected - offer.source.id),
            )
        }
        val mods = game.resolve("mods")
        for ((offer, _, _) in verified) require(!Files.exists(mods.resolve(offer.filename))) {
            "Dependency file already exists"
        }
        check(!Thread.currentThread().isInterrupted)
        for ((offer, file, _) in verified) Files.move(file, mods.resolve(offer.filename))
        return installed + verified.associate { (offer, _, version) -> offer.source.id to version }
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
        if (!environment.officialRelease) return
        // Only an update the player approved this session is ever staged.
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
