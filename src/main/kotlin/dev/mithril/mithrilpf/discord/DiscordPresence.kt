package dev.mithril.mithrilpf.discord

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.slf4j.LoggerFactory

/**
 * Conflated immutable input from Minecraft; all local IPC is worker-owned. A separate watchdog
 * closes stalled I/O. No unbounded queue, render-thread work or Discord credentials.
 */
class DiscordPresence(
    private val connect: () -> DiscordIpc = DiscordIpc::connect,
    private val timeoutMillis: Long = 5000,
    private val retryMillis: Long = 30000,
    private val updateMillis: Long = 5000,
    private val reportFailure: (String) -> Unit = {
        LoggerFactory.getLogger("MithrilPF Discord").warn(it)
    },
) : AutoCloseable {
    private val active = AtomicReference<DiscordIpc?>()
    @Volatile private var desired: DiscordActivity? = null
    @Volatile private var closed = false
    @Volatile private var deadline = Long.MAX_VALUE
    private var stage = "connect"
    private var lastFailure: String? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "MithrilPF Discord timeout").apply { isDaemon = true }
    }
    private val worker = Thread(::run, "MithrilPF Discord IPC").apply { isDaemon = true }

    init {
        watchdog.scheduleWithFixedDelay(
            {
                if (closed || desired == null || System.nanoTime() >= deadline) disconnect()
            },
            0,
            100,
            TimeUnit.MILLISECONDS,
        )
        worker.start()
    }

    fun update(value: DiscordActivity?) {
        if (closed || desired == value) return
        desired = value
        LockSupport.unpark(worker)
    }

    private fun run() {
        try {
            while (!closed) {
                if (desired == null) {
                    pause(1000)
                    continue
                }
                try {
                    session()
                } catch (failure: Exception) {
                    // Log only the stage/type, never messages or IPC payloads containing account
                    // data.
                    if (!closed && desired != null) {
                        val summary = "$stage (${failure.javaClass.simpleName})"
                        if (summary != lastFailure) {
                            lastFailure = summary
                            reportFailure("Rich Presence failed during $summary; will retry.")
                        }
                    }
                    disconnect()
                    val retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryMillis)
                    while (!closed && desired != null && System.nanoTime() < retryAt) {
                        pause(
                            TimeUnit.NANOSECONDS.toMillis(retryAt - System.nanoTime())
                                .coerceAtLeast(1)
                        )
                    }
                } finally {
                    disconnect()
                }
            }
        } catch (_: InterruptedException) {
            // Shutdown; the watchdog releases any in-flight native I/O.
        } finally {
            disconnect()
            watchdog.shutdown()
        }
    }

    private fun session() {
        stage = "connect"
        val ipc = connect()
        active.set(ipc)
        if (closed || desired == null) return
        stage = "handshake"
        timed { ipc.handshake() }
        var sent: DiscordActivity? = null
        var next = 0L
        var refresh = 0L
        var nonce = 0L
        while (!closed && desired != null && active.get() === ipc) {
            val value = desired ?: break
            val now = System.nanoTime()
            if ((sent != value && now >= next) || now >= refresh) {
                stage = "activity update"
                timed { ipc.activity(value, (++nonce).toString()) }
                lastFailure = null
                sent = value
                next = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(updateMillis)
                // Drain pings/detect a closed desktop client even for a static activity.
                refresh = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            }
            pause(250)
        }
    }

    private fun timed(action: () -> Unit) {
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            action()
        } finally {
            deadline = Long.MAX_VALUE
        }
    }

    private fun pause(millis: Long) {
        if (!closed) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis))
        if (Thread.interrupted()) throw InterruptedException()
    }

    private fun disconnect() {
        try {
            active.getAndSet(null)?.close()
        } catch (_: Exception) {
            /* Already disconnected. */
        }
    }

    override fun close() {
        closed = true
        desired = null
        // Closing local IPC clears this process's activity. The game never waits for native I/O.
        LockSupport.unpark(worker)
    }
}
