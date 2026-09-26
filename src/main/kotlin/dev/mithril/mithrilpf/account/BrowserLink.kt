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
    private val lifecycle = Any()
    private var screenOpen = false
    private var account = ""
    private var receipt: LinkReceipt? = null
    private var candidate: LinkReceipt? = null
    private var deadline = Long.MAX_VALUE
    var issued: IssuedLink? = null
        private set

    var qr: LinkQr? = null
        private set

    private var nextCheck = Long.MAX_VALUE
    var status = "ready"
        private set

    var working = false
        private set

    val linked: Boolean
        get() = receipt?.confirmed == true

    val secondsLeft: Long
        get() = TimeUnit.NANOSECONDS.toSeconds((deadline - System.nanoTime()).coerceAtLeast(0))

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
        if (issued != null && System.nanoTime() >= deadline) {
            // An expired attempt does not remove the previously confirmed receipt.
            synchronized(lifecycle) { generation++ }
            pending?.cancel(true)
            issued = null
            qr = null
            candidate = null
            working = false
            status = "code_expired"
            nextCheck = Long.MAX_VALUE
        }
        if (!screenOpen || working || System.nanoTime() < nextCheck) return
        val replacement = candidate != null
        val saved = candidate ?: receipt ?: return
        val active = receipt
        val uuid = account
        val attempt = generation
        working = true
        worker.purge()
        pending = worker.submit {
            try {
                val result = flow.status(saved.token, uuid)
                val updated = LinkPromotion.saved(active, saved, result, replacement)
                synchronized(lifecycle) {
                    if (attempt != generation) return@submit
                    if (updated != active) store.save(uuid, updated)
                }
                client.execute {
                    if (attempt == generation) {
                        receipt = updated
                        if (result != "pending") {
                            candidate = null
                            issued = null
                            qr = null
                        }
                        status =
                            when (result) {
                                "linked" -> "linked"
                                "pending" -> "opened"
                                else -> if (replacement) "code_expired" else "expired"
                            }
                        working = false
                        nextCheck =
                            if (result == "expired" || updated == null && !replacement)
                                Long.MAX_VALUE
                            else
                                System.nanoTime() +
                                    TimeUnit.SECONDS.toNanos(if (result == "linked") 30 else 3)
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

    fun openLink() {
        issued?.let { Util.getPlatform().openUri(it.uri) }
    }

    fun copyLink() {
        issued?.let { client.keyboardHandler.clipboard = it.uri.toString() }
    }

    fun start() {
        if (working) return
        status = "busy"
        working = true
        nextCheck = Long.MAX_VALUE
        val attempt = synchronized(lifecycle) { ++generation }
        candidate = null
        issued = null
        qr = null
        val user = client.user
        val uuid = user.profileId.toString().replace("-", "")
        val service = client.services().sessionService()
        worker.purge()
        pending = worker.submit {
            try {
                val newLink =
                    flow.run(uuid, user.name) { serverId ->
                        service.joinServer(user.profileId, user.accessToken, serverId)
                    }
                val generatedQr = LinkQr(newLink.uri)
                val expires =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(newLink.lifetime.toLong())
                client.execute {
                    if (attempt == generation) {
                        issued = newLink
                        qr = generatedQr
                        deadline = expires
                        candidate = newLink.receipt?.let { LinkReceipt(it, false) }
                        status = "opened"
                        working = false
                        nextCheck =
                            if (candidate == null) Long.MAX_VALUE
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
        synchronized(lifecycle) { generation++ }
        pending?.cancel(true)
        pending = null
        working = false
        nextCheck = Long.MAX_VALUE
        issued = null
        qr = null
        candidate = null
    }

    override fun close() {
        cancel()
        worker.shutdownNow()
    }
}
