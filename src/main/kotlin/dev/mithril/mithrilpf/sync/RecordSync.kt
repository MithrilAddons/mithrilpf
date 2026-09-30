package dev.mithril.mithrilpf.sync

import dev.mithril.mithrilpf.account.LinkReceiptStore
import dev.mithril.mithrilpf.account.LinkTransport
import dev.mithril.mithrilpf.dungeontimer.DungeonTimers
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

/** Client owns scheduling/status; one bounded worker owns receipts and the sync flow. */
class RecordSync(private val client: Minecraft) : AutoCloseable {
    private val worker =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1)) { task ->
            Thread(task, "MithrilPF record sync").apply { isDaemon = true }
        }
    private val store =
        LinkReceiptStore(FabricLoader.getInstance().configDir.resolve("mithrilpf/link.json"))
    private val flow = RecordSyncFlow(LinkTransport::syncPost)
    private val live = LiveRecordFlow(LinkTransport::syncPost)
    private var pending: Future<*>? = null
    private var account = ""
    private var generation = 0
    private var nextCheck = 0L
    private var busy = false
    private var failures = 0
    var status = "waiting"
        private set

    fun tick() {
        val user = client.user
        val uuid = user.profileId.toString().replace("-", "")
        if (account != uuid) {
            pending?.cancel(true)
            generation++
            account = uuid
            nextCheck = 0
            busy = false
            failures = 0
            status = "waiting"
            DungeonTimers.clearSyncEvents()
        }
        if (busy || !DungeonTimers.ready) return
        val event = DungeonTimers.pollSyncEvent()
        if (event == null && System.nanoTime() < nextCheck) return
        nextCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        val service = client.services().sessionService()
        val attempt = generation
        busy = true
        worker.purge()
        pending =
            try {
                worker.submit {
                    val result =
                        try {
                            val receipt = store.load(uuid)
                            if (event != null && !live.accepts(event)) {
                                "local_only"
                            } else if (receipt == null) {
                                flow.clear()
                                live.clear()
                                "unlinked"
                            } else {
                                val credential =
                                    flow.authenticate(uuid, user.name, receipt.token) { serverId ->
                                        service.joinServer(
                                            user.profileId,
                                            user.accessToken,
                                            serverId,
                                        )
                                    }
                                if (event == null) null else live.send(event, credential)
                            }
                        } catch (_: Exception) {
                            // Never log exceptions: HTTP/proof failures can include credentials.
                            flow.clear()
                            live.clear()
                            "unavailable"
                        }
                    client.execute {
                        if (generation == attempt) {
                            busy = false
                            if (result != null) status = result
                            failures =
                                if (result == "unavailable") (failures + 1).coerceAtMost(4) else 0
                            val delay = if (failures == 0) 30L else minOf(300L, 30L shl failures)
                            nextCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(delay)
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                busy = false
                status = "unavailable"
                null
            }
    }

    override fun close() {
        generation++
        pending?.cancel(true)
        worker.shutdownNow()
    }
}
