package dev.mithril.mithrilpf.party

import dev.mithril.mithrilpf.account.ServiceFailure
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Caller/client-thread owned. Two bounded lanes keep a held read from delaying a send. */
class PartyChatRelay(
    private val post: (String, com.google.gson.JsonObject, String) -> String,
    private val dispatch: (() -> Unit) -> Unit,
    private val display: (PartyChatMessage) -> Unit,
    private val notice: (String, String?) -> Unit,
    private val unauthorized: () -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private fun worker(name: String) =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1)) { task ->
            Thread(task, name).apply { isDaemon = true }
        }

    private val reader = worker("MithrilPF chat receive")
    private val sender = worker("MithrilPF chat send")
    private var readTask: Future<*>? = null
    private var sendTask: Future<*>? = null
    private var access: ChatAccess? = null
    private var generation = 0
    private var reading = false
    private var sending = false
    private var enabled = false
    private var closed = false
    private var nextRead = 0L
    private var failures = 0
    private val timeline = PartyChatTimeline()
    private var retry: Retry? = null

    private class Retry(val text: String, val id: String, val at: Long)

    fun update(next: ChatAccess?) {
        if (closed || (access == null && next == null) || access?.same(next) == true) return
        val changedParty = access?.account != next?.account || access?.partyId != next?.partyId
        cancel()
        access = next
        if (changedParty) {
            timeline.reset()
            retry = null
            if (next != null) notice("ready", null)
        }
        failures = 0
        nextRead = 0
    }

    fun tick(active: Boolean) {
        if (closed) return
        if (!active) {
            if (enabled) cancel()
            enabled = false
            return
        }
        enabled = true
        val current = access ?: return
        if (reading || clock() < nextRead) return
        val attempt = generation
        val after = timeline.cursor
        reading = true
        reader.purge()
        readTask = reader.submit {
            val result = attempt {
                PartyChatProtocol.batch(
                    post("state", PartyChatProtocol.read(current.partyId, after), current.token),
                    current.partyId,
                    after,
                )
            }
            dispatch {
                if (!closed && generation == attempt) {
                    reading = false
                    result.fold(
                        { batch ->
                            timeline.receive(batch).forEach(display)
                            failures = 0
                            nextRead =
                                clock() + 250 // Also bound unexpectedly immediate empty replies.
                        },
                        { error ->
                            if (error is ServiceFailure && error.statusCode in setOf(401, 409)) {
                                update(null)
                                unauthorized()
                            } else {
                                failures = (failures + 1).coerceAtMost(5)
                                nextRead = clock() + minOf(30_000L, 1000L shl failures)
                                if (failures == 1) notice("reconnecting", null)
                            }
                        },
                    )
                }
            }
        }
    }

    fun send(raw: String) {
        val text = raw.trim()
        val current = access
        if (closed || !enabled || current == null) {
            notice("unavailable", null)
            return
        }
        if (!PartyChatProtocol.validText(text)) {
            notice("invalid", null)
            return
        }
        if (sending) {
            notice("busy", null)
            return
        }
        val outgoing =
            retry?.takeIf { it.text == text && clock() - it.at < 590_000 }
                ?: Retry(text, UUID.randomUUID().toString(), clock()).also { retry = it }
        val attempt = generation
        sending = true
        sender.purge()
        sendTask = sender.submit {
            val result = attempt {
                PartyChatProtocol.acknowledgement(
                    post(
                        "send",
                        PartyChatProtocol.send(current.partyId, text, outgoing.id),
                        current.token,
                    ),
                    current.account,
                    text,
                )
            }
            dispatch {
                if (!closed && generation == attempt) {
                    sending = false
                    result.fold(
                        { message ->
                            retry = null
                            if (timeline.acknowledge(message)) display(message)
                        },
                        { error ->
                            val status = (error as? ServiceFailure)?.statusCode
                            if (status in setOf(401, 409)) {
                                update(null)
                                unauthorized()
                                notice("unavailable", null)
                            } else notice(if (status == 429) "limited" else "failed", text)
                        },
                    )
                }
            }
        }
    }

    private fun cancel() {
        generation++
        readTask?.cancel(true)
        sendTask?.cancel(true)
        reading = false
        sending = false
        reader.purge()
        sender.purge()
    }

    private fun <T> attempt(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    override fun close() {
        closed = true
        cancel()
        access = null
        retry = null
        timeline.reset()
        reader.shutdownNow()
        sender.shutdownNow()
    }
}

/** Read cursor never advances on a send acknowledgement (other messages may precede it). */
class PartyChatTimeline {
    var cursor = 0L
        private set

    private var initialized = false
    private val shown = LinkedHashSet<Long>()

    fun reset() {
        cursor = 0
        initialized = false
        shown.clear()
    }

    fun receive(batch: PartyChatBatch): List<PartyChatMessage> {
        val messages = if (initialized) batch.messages else batch.messages.takeLast(10)
        val fresh = messages.filter { acknowledge(it) }
        cursor = batch.latest
        initialized = true
        return fresh
    }

    fun acknowledge(message: PartyChatMessage): Boolean {
        if (message.id <= cursor || !shown.add(message.id)) return false
        while (shown.size > 200) shown.remove(shown.first())
        return true
    }
}
