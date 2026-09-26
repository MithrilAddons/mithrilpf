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
        }
        if (busy || System.nanoTime() < nextCheck || !DungeonTimers.ready) return
        nextCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        val snapshot = DungeonTimers.syncSnapshot(user.profileId.toString())
        if (snapshot.records.isEmpty()) {
            status = "no_records"
            return
        }
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
                            if (receipt == null) {
                                flow.clear()
                                "unlinked"
                            } else {
                                flow.sync(uuid, user.name, receipt.token, snapshot) { serverId ->
                                    service.joinServer(user.profileId, user.accessToken, serverId)
                                }
                                "synced"
                            }
                        } catch (_: Exception) {
                            // Never log exceptions: HTTP/proof failures can include credentials.
                            "unavailable"
                        }
                    client.execute {
                        if (generation == attempt) {
                            busy = false
                            status = result
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
