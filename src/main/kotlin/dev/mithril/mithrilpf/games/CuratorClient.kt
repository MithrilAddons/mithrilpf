package dev.mithril.mithrilpf.games

import com.google.gson.JsonObject
import dev.mithril.mithrilpf.account.LinkTransport
import dev.mithril.mithrilpf.account.NativeAccount
import dev.mithril.mithrilpf.account.ServiceFailure
import java.net.URLEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

interface GamesHost {
    val uuid: String
    val token: String?

    fun execute(action: () -> Unit)

    /** Unix seconds. */
    fun now(): Long
}

/**
 * Curator state for the Games screen. Network and disk work run on one bounded worker; results are
 * applied on the client thread and dropped if the account changed meanwhile.
 */
class CuratorClient
internal constructor(
    private val host: GamesHost,
    private val store: CuratorCatalogStore,
    private val request: (String, JsonObject?, String) -> String = LinkTransport::gameRequest,
) : AutoCloseable {
    constructor(
        client: Minecraft,
        account: NativeAccount,
    ) : this(
        object : GamesHost {
            override val uuid
                get() = client.user.profileId.toString().replace("-", "")

            override val token
                get() = account.session?.token

            override fun execute(action: () -> Unit) = client.execute(action)

            override fun now() = System.currentTimeMillis() / 1000
        },
        CuratorCatalogStore(FabricLoader.getInstance().configDir.resolve("mithrilpf/curator.json")),
    )

    private val worker =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4)) { task ->
            Thread(task, "MithrilPF games").apply { isDaemon = true }
        }
    private var identity = ""
    private var credential: String? = null
    private var generation = 0
    private var loadingToday = false
    private var loadingBoard = false
    private var catalogRequested = false
    private var nextPoll = 0L

    var catalog: Catalog? = null
        private set

    var today: CuratorDay? = null
        private set

    var board: Leaderboard? = null
        private set

    /** A guess is on its way; the guess box stays disabled until it returns. */
    var guessing = false
        private set

    /** The UTC day rolled over while the screen was open. */
    var newDay = false
        private set

    /** A translation key under games.mithrilpf.error. */
    var error: String? = null
        private set

    var revision = 0
        private set

    val signedIn
        get() = host.token != null

    fun tick(visible: Boolean) {
        if (identity != host.uuid || credential != host.token) reset()
        val token = credential ?: return
        if (!visible) return
        if (!catalogRequested) loadCatalog(token)
        val day = today
        val now = host.now()
        when {
            day == null -> if (!loadingToday) loadToday(token)
            day.state == RoundState.PREPARING ->
                if (!loadingToday && now >= nextPoll) loadToday(token)
            now >= day.resetsAt && !newDay -> {
                newDay = true
                revision++
            }
        }
    }

    /** Fetch the new day after the reset, from the status line's Load button. */
    fun reload() {
        today = null
        board = null
        newDay = false
        error = null
        revision++
    }

    fun guess(item: CatalogItem) {
        val token = credential ?: return
        val day = today ?: return
        if (guessing || day.state != RoundState.PLAYING) return
        if (day.guesses.any { it.item == item.id }) {
            error = "already_guessed"
            revision++
            return
        }
        guessing = true
        error = null
        revision++
        submit(
            onFailure = { failure ->
                guessing = false
                if (failure is ServiceFailure && failure.detail == NEW_DAY) newDay = true
                fail(failure)
            }
        ) {
            val result =
                CuratorProtocol.day(
                    request(
                        "games/curator/guess",
                        CuratorProtocol.guessBody(day.day, item.id),
                        token,
                    )
                )
            return@submit {
                guessing = false
                today = result
                board = null
            }
        }
    }

    fun loadBoard() {
        val token = credential ?: return
        if (loadingBoard) return
        loadingBoard = true
        submit(
            onFailure = {
                loadingBoard = false
                fail(it)
            }
        ) {
            val result =
                CuratorProtocol.leaderboard(request("games/curator/leaderboard", null, token))
            return@submit {
                loadingBoard = false
                board = result
            }
        }
    }

    private fun loadToday(token: String) {
        loadingToday = true
        submit(
            onFailure = {
                loadingToday = false
                nextPoll = host.now() + RETRY
                fail(it)
            }
        ) {
            val result = CuratorProtocol.day(request("games/curator/today", null, token))
            return@submit {
                loadingToday = false
                today = result
                nextPoll = host.now() + RETRY
                if (result.state != RoundState.PREPARING) error = null
            }
        }
    }

    private fun loadCatalog(token: String) {
        catalogRequested = true
        submit(onFailure = { catalogRequested = false }) {
            val cached = store.load()
            val version = cached?.version?.let { URLEncoder.encode(it, Charsets.UTF_8) }
            val path = "games/curator/catalog" + (version?.let { "?version=$it" } ?: "")
            val fresh = CuratorProtocol.catalog(request(path, null, token), cached)
            if (fresh !== cached) store.save(fresh)
            return@submit { catalog = fresh }
        }
    }

    /** Runs [work] off-thread; the returned action is applied on the client thread. */
    private fun submit(onFailure: (Throwable) -> Unit, work: () -> () -> Unit) {
        val attempt = generation
        try {
            worker.execute {
                val result = runCatching(work)
                host.execute {
                    if (attempt != generation) return@execute
                    result.fold({ it() }, onFailure)
                    revision++
                }
            }
        } catch (_: RejectedExecutionException) {
            onFailure(IllegalStateException("Games worker busy"))
        }
    }

    private fun fail(failure: Throwable) {
        error =
            when {
                failure !is ServiceFailure -> "unavailable"
                failure.statusCode == 401 -> "signed_out"
                failure.reason == "banned" -> "banned"
                else -> DETAILS[failure.detail] ?: "unavailable"
            }
    }

    private fun reset() {
        generation++
        identity = host.uuid
        credential = host.token
        catalog = null
        catalogRequested = false
        today = null
        board = null
        guessing = false
        loadingToday = false
        loadingBoard = false
        newDay = false
        error = null
        nextPoll = 0
        revision++
    }

    override fun close() {
        worker.shutdownNow()
    }

    internal companion object {
        const val NEW_DAY = "A new item is ready"
        const val RETRY = 15L

        /** Known backend messages; anything else reads as the service being unavailable. */
        private val DETAILS =
            mapOf(
                NEW_DAY to "new_day",
                "Already guessed" to "already_guessed",
                "Unknown item" to "unknown_item",
                "Today's round is already over" to "round_over",
                "Today's item is still being prepared" to "preparing",
            )
    }
}
