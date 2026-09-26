package dev.mithril.mithrilpf.dungeontimer

import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/** One bounded worker owns all stores. Callbacks publish through the supplied client executor. */
class TrackingStorage(private val directory: Path, private val publish: (() -> Unit) -> Unit) :
    AutoCloseable {
    private val log = LoggerFactory.getLogger("MithrilPF tracking")
    private val worker =
        ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(128),
            { task -> Thread(task, "MithrilPF records").apply { isDaemon = true } },
        )
    private val config = TrackingSettingsStore(directory.resolve("tracking.json"))
    private val bests = mutableMapOf<String, DungeonPersonalBests>()
    private val history = DungeonRunStore(directory.resolve("runs"))
    var ready = false
        private set

    var error = false
        private set

    var records: Map<String, Map<String, Map<String, Map<String, DungeonBest>>>> = emptyMap()
        private set

    var statistics: Map<String, Map<String, DungeonRunStatistics>> = emptyMap()
        private set

    fun load(loaded: (TrackingSettings) -> Unit) = submit {
        val settings = config.load()
        for (kind in listOf("splits", "rooms", "solo")) {
            bests[kind] = DungeonPersonalBests(directory.resolve(kind)).apply { load() }
        }
        history.load { file, ex -> log.warn("Ignoring invalid run {}", file.fileName, ex) }
        val snapshot = bests.mapValues { it.value.records }
        val stats = history.summaries()
        publish {
            records = snapshot
            statistics = stats
            ready = true
            loaded(settings)
        }
    }

    fun settings(settings: TrackingSettings) {
        if (ready && !error) submit { config.save(settings) }
    }

    fun record(
        kind: String,
        player: String,
        floor: String,
        times: Map<String, SplitTime>,
        done: (Map<String, DungeonBest>, Map<String, SplitTime>) -> Unit,
    ) {
        if (!ready || error || times.isEmpty()) return
        val frozen = times.toMap()
        submit {
            val store = bests.getValue(kind)
            val previous = store.records[player]?.get(floor).orEmpty()
            val changed = store.record(player, floor, frozen)
            val snapshot = bests.mapValues { it.value.records }
            publish {
                records = snapshot
                done(previous, changed)
            }
        }
    }

    fun append(run: DungeonRunRecord) {
        if (!ready || error) return
        submit {
            history.append(run)
            val stats = history.summaries()
            publish { statistics = stats }
        }
    }

    private fun submit(action: () -> Unit) {
        try {
            worker.execute {
                try {
                    action()
                } catch (e: Exception) {
                    failed(e)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            failed(e)
        }
    }

    private fun failed(e: Exception) {
        log.error("Tracking storage unavailable; existing data retained", e)
        publish { error = true }
    }

    override fun close() {
        worker.shutdown()
        worker.awaitTermination(2, TimeUnit.SECONDS)
    }
}
