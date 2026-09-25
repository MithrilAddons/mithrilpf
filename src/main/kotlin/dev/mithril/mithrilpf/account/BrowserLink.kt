package dev.mithril.mithrilpf.account

import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.util.Util

/** UI state is client-thread owned; bounded worker owns all disk/network operations. */
class BrowserLink(private val client: Minecraft) : AutoCloseable {
    private val worker =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1)) { task ->
            Thread(task, "MithrilPF account link").apply { isDaemon = true }
        }
    private val store =
        LinkReceiptStore(FabricLoader.getInstance().configDir.resolve("mithrilpf/link.json"))
    private val flow = LinkFlow(LinkTransport::post)
    private var pending: Future<*>? = null
    private var generation = 0
    private var screenOpen = false
    private var account = ""
    private var receipt: LinkReceipt? = null
    private var nextCheck = Long.MAX_VALUE
    var status = "ready"
        private set

    var working = false
        private set

    val linked: Boolean
        get() = receipt?.confirmed == true

    fun show() {
        val uuid = client.user.profileId.toString().replace("-", "")
        if (screenOpen && account == uuid) return
        cancel()
        screenOpen = true
        account = uuid
        receipt = null
        status = "checking"
        working = true
        val attempt = generation
        worker.purge()
        pending = worker.submit {
            try {
                val saved = store.load(uuid)
                client.execute {
                    if (attempt == generation) {
                        receipt = saved
                        status =
                            if (saved?.confirmed == true) "linked"
                            else if (saved != null) "opened" else "ready"
                        working = false
                        nextCheck = if (saved != null) 0 else Long.MAX_VALUE
                    }
                }
            } catch (_: Exception) {
                client.execute {
                    if (attempt == generation) {
                        status = "storage_failed"
                        working = false
                    }
                }
            }
        }
    }

    fun tick() {
        if (screenOpen && account != client.user.profileId.toString().replace("-", "")) show()
        if (!screenOpen || working || System.nanoTime() < nextCheck) return
        val saved = receipt ?: return
        val uuid = account
        val attempt = generation
        working = true
        worker.purge()
        pending = worker.submit {
            try {
                val result = flow.status(saved.token, uuid)
                val updated =
                    when (result) {
                        "linked" -> saved.copy(confirmed = true)
                        "pending" -> saved.copy(confirmed = false)
                        else -> null
                    }
                if (updated != saved) store.save(uuid, updated)
                client.execute {
                    if (attempt == generation) {
                        receipt = updated
                        status =
                            when (result) {
                                "linked" -> "linked"
                                "pending" -> "opened"
                                else -> "expired"
                            }
                        working = false
                        nextCheck =
                            if (updated == null) Long.MAX_VALUE
                            else
                                System.nanoTime() +
                                    TimeUnit.SECONDS.toNanos(if (updated.confirmed) 30 else 3)
                    }
                }
            } catch (_: Exception) {
                client.execute {
                    if (attempt == generation) {
                        status = if (saved.confirmed) "linked_offline" else "unavailable"
                        working = false
                        nextCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    }
                }
            }
        }
    }

    fun openWebsite() = Util.getPlatform().openUri(URI("https://mithril.foo/party-finder"))

    fun start() {
        if (working) return
        status = "busy"
        working = true
        nextCheck = Long.MAX_VALUE
        val attempt = ++generation
        val user = client.user
        val uuid = user.profileId.toString().replace("-", "")
        val service = client.services().sessionService()
        worker.purge()
        pending = worker.submit {
            try {
                val issued =
                    flow.run(uuid, user.name) { serverId ->
                        service.joinServer(user.profileId, user.accessToken, serverId)
                    }
                val saved = issued.receipt?.let { LinkReceipt(it, false) }
                store.save(uuid, saved)
                client.execute {
                    if (attempt == generation) {
                        receipt = saved
                        Util.getPlatform().openUri(issued.uri)
                        status = "opened"
                        working = false
                        nextCheck =
                            if (saved == null) Long.MAX_VALUE
                            else System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    }
                }
            } catch (_: Exception) {
                // Exceptions may carry request data: never log credentials or bearer URLs.
                client.execute {
                    if (attempt == generation) {
                        status = "failed"
                        working = false
                    }
                }
            }
        }
    }

    fun cancel() {
        screenOpen = false
        generation++
        pending?.cancel(true)
        pending = null
        working = false
        nextCheck = Long.MAX_VALUE
    }

    override fun close() {
        cancel()
        worker.shutdownNow()
    }
}
