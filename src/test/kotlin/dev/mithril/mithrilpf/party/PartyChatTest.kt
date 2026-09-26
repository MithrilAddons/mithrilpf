package dev.mithril.mithrilpf.party

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.mithril.mithrilpf.account.ServiceFailure
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PartyChatTest {
    private val account = "a".repeat(32)
    private val party = "party0000001"
    private val token = "t".repeat(43)

    private fun access() = ChatAccess(account, party, token)

    private fun message(id: Int, text: String = "hello") =
        JsonObject().apply {
            addProperty("id", id.toString())
            addProperty("text", text)
            addProperty("source", "game")
            add(
                "sender",
                JsonObject().apply {
                    addProperty("uuid", account)
                    addProperty("name", "Alpha")
                },
            )
        }

    private fun batch(first: Int = 1, last: Int = 1) =
        JsonObject().apply {
            addProperty("version", 1)
            addProperty("party_id", party)
            addProperty("latest", last)
            add("messages", JsonArray().apply { for (id in first..last) add(message(id)) })
        }

    private fun ack() =
        JsonObject()
            .apply {
                addProperty("version", 1)
                add("message", message(1))
            }
            .toString()

    @Test
    fun `text permits emoji but bounds codepoints and rejects formatting and controls`() {
        for (size in listOf(1, 255, 256)) assertTrue(PartyChatProtocol.validText("x".repeat(size)))
        assertTrue(PartyChatProtocol.validText("👋".repeat(256)))
        for (text in
            listOf(
                "",
                "  ",
                "x".repeat(257),
                "hello\nworld",
                "§ahello",
                "\u0000",
                "\u202Eevil",
                "\uD800",
            )) assertFalse(PartyChatProtocol.validText(text), text)
    }

    @Test
    fun `strict bounded parser accepts server messages without interpreting markup`() {
        val raw = batch()
        raw.getAsJsonArray("messages")[0]
            .asJsonObject
            .addProperty("text", "/party leave <b>hello</b>")
        assertEquals(
            "/party leave <b>hello</b>",
            PartyChatProtocol.batch(raw.toString(), party, 0).messages.single().text,
        )
        assertFails { PartyChatProtocol.batch(" ".repeat(262145), party, 0) }
        assertFails { PartyChatProtocol.batch(batch(1, 101).toString(), party, 0) }
        assertEquals(100, PartyChatProtocol.batch(batch(1, 100).toString(), party, 0).messages.size)
        assertFails { PartyChatProtocol.batch(batch().toString(), "other0000001", 0) }
        assertFails { PartyChatProtocol.batch(batch().toString(), party, 2) }
        raw.getAsJsonArray("messages").add(message(1))
        assertFails { PartyChatProtocol.batch(raw.toString(), party, 0) }
        for (bad in listOf("§khidden", "\u0000", "x".repeat(257))) {
            val invalid = batch()
            invalid.getAsJsonArray("messages")[0].asJsonObject.addProperty("text", bad)
            assertFails { PartyChatProtocol.batch(invalid.toString(), party, 0) }
        }
        assertFails { PartyChatProtocol.acknowledgement(ack(), "b".repeat(32), "hello") }
        assertFails { PartyChatProtocol.acknowledgement(ack(), account, "other") }
        assertFalse(access().toString().contains(token))
    }

    @Test
    fun `initial history is limited and acknowledgement never skips other messages`() {
        val timeline = PartyChatTimeline()
        val first = PartyChatProtocol.batch(batch(1, 100).toString(), party, 0)
        assertEquals((91L..100L).toList(), timeline.receive(first).map { it.id })
        val sent = PartyChatMessage(103, account, "Alpha", "hello", "game")
        assertTrue(timeline.acknowledge(sent))
        assertEquals(100L, timeline.cursor)
        val next = PartyChatProtocol.batch(batch(101, 103).toString(), party, 100)
        assertEquals(listOf(101L, 102L), timeline.receive(next).map { it.id })
        assertFalse(timeline.acknowledge(sent))
        timeline.reset()
        assertEquals(0L, timeline.cursor)
        assertEquals(3, timeline.receive(next).size)
    }

    private inner class Harness(post: (String, JsonObject, String) -> String) : AutoCloseable {
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val displayed = mutableListOf<PartyChatMessage>()
        val notices = mutableListOf<Pair<String, String?>>()
        var refreshes = 0
        var time = 0L
        val relay =
            PartyChatRelay(
                post,
                { callbacks.add(it) },
                { displayed.add(it) },
                { key, text -> notices.add(key to text) },
                { refreshes++ },
                { time },
            )

        fun complete() = assertNotNull(callbacks.poll(3, TimeUnit.SECONDS)).invoke()

        override fun close() = relay.close()
    }

    @Test
    fun `held receive does not block sends and uncertain delivery has explicit idempotent retry`() {
        val requestIds = LinkedBlockingQueue<String>()
        val holdRead = CountDownLatch(1)
        var sends = 0
        Harness { path, body, _ ->
            if (path == "state") {
                holdRead.await()
                batch().toString()
            } else {
                requestIds.add(body["request_id"].asString)
                sends++
                if (sends == 1) throw IOException("synthetic outage")
                ack()
            }
        }
            .use { h ->
                h.relay.update(access())
                h.relay.tick(true)
                h.relay.send(" hello ")
                h.complete()
                assertEquals("failed" to "hello", h.notices.last())
                assertEquals(1, sends) // No automatic replay.
                h.relay.send("hello")
                h.complete()
                assertEquals(requestIds.remove(), requestIds.remove())
                assertEquals(1, h.displayed.size)
                holdRead.countDown()
                h.complete()
                assertEquals(1, h.displayed.size) // Receive echo and acknowledgement deduplicated.
            }
    }

    @Test
    fun `stale account and party replies never reach the game`() {
        Harness { _, _, _ -> batch().toString() }
            .use { h ->
                h.relay.update(access())
                h.relay.tick(true)
                val old = assertNotNull(h.callbacks.poll(3, TimeUnit.SECONDS))
                h.relay.update(ChatAccess("b".repeat(32), "other0000001", token))
                old()
                assertTrue(h.displayed.isEmpty())
                h.relay.update(null)
                h.relay.send("hello")
                assertEquals("unavailable", h.notices.last().first)
            }
    }

    @Test
    fun `logout stops polling and asks presence to refresh once`() {
        var calls = 0
        Harness { _, _, _ ->
            calls++
            throw ServiceFailure(401)
        }
            .use { h ->
                h.relay.update(access())
                h.relay.tick(true)
                h.complete()
                repeat(10) {
                    h.time += 60000
                    h.relay.tick(true)
                }
                assertEquals(1, calls)
                assertEquals(1, h.refreshes)
                assertTrue(h.displayed.isEmpty())
            }
    }

    @Test
    fun `outages back off and do not spam notices`() {
        var calls = 0
        Harness { _, _, _ ->
            calls++
            throw IOException()
        }
            .use { h ->
                h.relay.update(access())
                h.relay.tick(true)
                h.complete()
                h.time = 1999
                h.relay.tick(true)
                assertEquals(1, calls)
                h.time = 2000
                h.relay.tick(true)
                h.complete()
                assertEquals(2, calls)
                assertEquals(1, h.notices.count { it.first == "reconnecting" })
            }
    }

    @Test
    fun `shutdown interrupts blocked reads and drops already queued callbacks`() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        Harness { _, _, _ ->
            entered.countDown()
            try {
                CountDownLatch(1).await()
                batch().toString()
            } finally {
                interrupted.countDown()
            }
        }
            .use { h ->
                h.relay.update(access())
                h.relay.tick(true)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                h.relay.close()
                assertTrue(interrupted.await(3, TimeUnit.SECONDS))
                h.complete()
                assertTrue(h.displayed.isEmpty())
                assertEquals(0, h.refreshes)
            }
    }

    @Test
    fun `receiving is suspended outside a world and credential renewal preserves cursor`() {
        val requests = LinkedBlockingQueue<Long>()
        Harness { _, body, _ ->
            val after = body["after"].asLong
            requests.add(after)
            if (after == 0L) batch().toString() else batch(2, 1).toString()
        }
            .use { h ->
                h.relay.update(access())
                h.relay.tick(false)
                assertTrue(requests.isEmpty())
                h.relay.tick(true)
                h.complete()
                h.relay.update(ChatAccess(account, party, "n".repeat(43)))
                h.relay.tick(true)
                h.complete()
                assertEquals(listOf(0L, 1L), requests.toList())
                assertEquals(1, h.displayed.size)
            }
    }
}
