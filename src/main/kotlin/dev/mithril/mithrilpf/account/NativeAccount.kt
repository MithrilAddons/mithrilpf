package dev.mithril.mithrilpf.account

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

/** Client-thread state; one bounded worker performs credential I/O and ownership verification. */
internal class DeviceIdentity(val name: String, val prove: (String) -> Unit)

class NativeAccount
internal constructor(
    private val store: DeviceSessionStore,
    private val flow: DeviceFlow,
    private val uuid: () -> String,
    private val signInIdentity: () -> DeviceIdentity,
    private val execute: (() -> Unit) -> Unit,
) : AutoCloseable {
    constructor(
        client: Minecraft
    ) : this(
        DeviceSessionStore(FabricLoader.getInstance().configDir.resolve("mithrilpf/device.json")),
        DeviceFlow(LinkTransport::finderRequest),
        { client.user.profileId.toString().replace("-", "") },
        {
            val user = client.user
            val service = client.services().sessionService()
            DeviceIdentity(user.name) { serverId ->
                service.joinServer(user.profileId, user.accessToken, serverId)
            }
        },
        { action -> client.execute(action) },
    )

    private val worker =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1)) { task ->
            Thread(task, "MithrilPF native account").apply { isDaemon = true }
        }
    private var pending: Future<*>? = null
    private var account = ""
    private var generation = 0
    var session: DeviceSession? = null
        private set

    var status = "signed_out"
        private set

    var busy = false
        private set

    var writable = false
        private set

    fun tick() {
        val uuid = uuid()
        if (uuid == account) return
        pending?.cancel(true)
        generation++
        account = uuid
        session = null
        writable = false
        busy = false
        load()
    }

    fun load() {
        if (busy) return
        val uuid = account
        submit("checking") {
            val saved = store.load(uuid)
            val result =
                if (saved == null) "signed_out"
                else
                    try {
                        flow.check(uuid, saved)
                        "signed_in"
                    } catch (failure: ServiceFailure) {
                        if (failure.statusCode != 401) throw failure
                        store.save(uuid, null)
                        "expired"
                    }
            val publish: () -> Unit = {
                session = if (result == "signed_in") saved else null
                writable = true
                status = result
            }
            publish
        }
    }

    fun signIn() {
        if (busy || !writable || session != null) return
        val user = signInIdentity()
        val uuid = account
        submit("signing_in") {
            val created = flow.signIn(uuid, user.name, user.prove)
            try {
                store.save(uuid, created)
            } catch (error: Exception) {
                // A credential that could not be saved should not consume a device slot.
                runCatching { flow.signOut(created) }
                throw error
            }
            val publish: () -> Unit = {
                session = created
                status = "signed_in"
            }
            publish
        }
    }

    fun signOut() {
        if (busy) return
        val saved = session ?: return
        val uuid = account
        submit("signing_out") {
            flow.signOut(saved)
            store.save(uuid, null)
            val publish: () -> Unit = {
                session = null
                status = "signed_out"
            }
            publish
        }
    }

    private fun submit(progress: String, action: () -> (() -> Unit)) {
        val attempt = generation
        busy = true
        status = progress
        worker.purge()
        pending = worker.submit {
            val publish =
                try {
                    action()
                } catch (_: Exception) {
                    // HTTP/proof exceptions can contain credentials: never log their details.
                    { status = "failed" }
                }
            execute {
                if (generation == attempt) {
                    publish()
                    busy = false
                }
            }
        }
    }

    override fun close() {
        generation++
        pending?.cancel(true)
        worker.shutdownNow()
    }
}
