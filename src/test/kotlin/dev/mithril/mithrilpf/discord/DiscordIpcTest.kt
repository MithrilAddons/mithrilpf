package dev.mithril.mithrilpf.discord

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DiscordIpcTest {
    private val value = DiscordActivity("M7 · Blood Rush", "In a dungeon", 100)

    private fun frame(op: Int, json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(8 + body.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(op)
            .putInt(body.size)
            .put(body)
            .array()
    }

    @Test
    fun `handshake ping pong and activity acknowledgement use bounded local protocol`() {
        val input =
            frame(1, """{"evt":"READY"}""") +
                frame(3, """{"probe":1}""") +
                frame(1, """{"cmd":"SET_ACTIVITY","nonce":"1","evt":null}""")
        val output = ByteArrayOutputStream()
        val ipc = DiscordIpc(ByteArrayInputStream(input), output) {}
        ipc.handshake()
        ipc.activity(value, "1")
        val frames = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        fun take(): Pair<Int, String> {
            val op = frames.int
            val body = ByteArray(frames.int).also(frames::get)
            return op to body.toString(Charsets.UTF_8)
        }
        val hello = take()
        assertEquals(0, hello.first)
        assertEquals(
            DiscordIpc.APPLICATION_ID,
            JsonParser.parseString(hello.second).asJsonObject["client_id"].asString,
        )
        val activity = take()
        assertEquals(1, activity.first)
        val args = JsonParser.parseString(activity.second).asJsonObject["args"].asJsonObject
        assertEquals(ProcessHandle.current().pid(), args["pid"].asLong)
        assertEquals(value.json(), args["activity"])
        assertEquals(4 to """{"probe":1}""", take())
        assertFalse(frames.hasRemaining())
    }

    @Test
    fun `partial frames oversized frames close malformed and rejected commands fail`() {
        val bad =
            listOf(
                byteArrayOf(1, 0),
                frame(1, "{} ").dropLast(1).toByteArray(),
                ByteBuffer.allocate(8)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(1)
                    .putInt(65537)
                    .array(),
                ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(-1).array(),
                frame(2, "{}"),
                frame(1, "["),
                frame(1, """{"evt":"ERROR"}"""),
            )
        for (bytes in bad) assertFails {
            DiscordIpc(ByteArrayInputStream(bytes), ByteArrayOutputStream()) {}.handshake()
        }
        assertFails {
            DiscordIpc(
                    ByteArrayInputStream(frame(1, """{"evt":"ERROR","nonce":"1"}""")),
                    ByteArrayOutputStream(),
                ) {}
                .activity(value, "1")
        }
    }

    /** Responds to writes without any native IPC or a real Discord account. */
    private inner class FakeDiscord(
        private val stall: Boolean = false,
        private val stallActivity: Boolean = false,
    ) {
        val closed = AtomicInteger()
        val commands = CopyOnWriteArrayList<String>()
        private val incoming = LinkedBlockingQueue<Int>()
        private val input =
            object : InputStream() {
                override fun read(): Int = incoming.take()

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    val first = read()
                    if (first == -1) return -1
                    bytes[offset] = first.toByte()
                    var count = 1
                    while (count < length) {
                        val next = incoming.poll() ?: break
                        if (next == -1) {
                            incoming.offer(-1)
                            break
                        }
                        bytes[offset + count++] = next.toByte()
                    }
                    return count
                }
            }
        private val output =
            object : ByteArrayOutputStream() {
                override fun flush() {
                    val bytes = toByteArray()
                    reset()
                    val json = bytes.copyOfRange(8, bytes.size).toString(Charsets.UTF_8)
                    commands.add(json)
                    if (stall) return
                    val request = JsonParser.parseString(json).asJsonObject
                    val nonce = request["nonce"]?.asString
                    if (nonce != null && stallActivity) return
                    val reply =
                        if (nonce == null) """{"evt":"READY"}"""
                        else """{"cmd":"SET_ACTIVITY","nonce":"$nonce","evt":null}"""
                    frame(1, reply).forEach { incoming.put(it.toInt() and 255) }
                }
            }
        val ipc =
            DiscordIpc(input, output) {
                closed.incrementAndGet()
                incoming.offer(-1)
            }
    }

    private fun await(test: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!test() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue(test(), "Timed out waiting for synthetic IPC")
    }

    @Test
    fun `stalled handshake and activity are closed once and reconnect without blocking caller`() {
        for (stallActivity in listOf(false, true)) {
            val stuck = FakeDiscord(stall = !stallActivity, stallActivity = stallActivity)
            val healthy = FakeDiscord()
            val attempts = AtomicInteger()
            DiscordPresence(
                    { if (attempts.incrementAndGet() == 1) stuck.ipc else healthy.ipc },
                    100,
                    50,
                    20,
                )
                .use { presence ->
                    presence.update(value)
                    await { healthy.commands.size >= 2 }
                    assertEquals(1, stuck.closed.get())
                    presence.update(null)
                    await { healthy.closed.get() == 1 }
                }
            assertEquals(1, stuck.closed.get())
        }
    }

    @Test
    fun `updates are conflated disabled clears and shutdown releases active read`() {
        val fake = FakeDiscord()
        val presence = DiscordPresence({ fake.ipc }, 1000, 1000, 100)
        try {
            presence.update(value)
            await { fake.commands.size >= 2 }
            repeat(50) { presence.update(value.copy(details = "M7 · $it")) }
            await { fake.commands.last().contains("M7 · 49") }
            assertTrue(fake.commands.size < 10)
            presence.update(null)
            await { fake.closed.get() == 1 }
        } finally {
            presence.close()
        }
        val blocked = FakeDiscord(stall = true)
        val pending = DiscordPresence({ blocked.ipc }, 30000)
        pending.update(value)
        await { blocked.commands.isNotEmpty() }
        pending.close()
        await { blocked.closed.get() == 1 }
    }

    @Test
    fun `Discord absent retries at bounded rate without touching caller`() {
        val attempts = AtomicInteger()
        DiscordPresence(
                {
                    attempts.incrementAndGet()
                    throw IOException("Not running")
                },
                100,
                500,
            )
            .use { presence ->
                presence.update(value)
                await { attempts.get() == 1 }
                repeat(20) { presence.update(value.copy(state = "State $it")) }
                Thread.sleep(100)
                assertEquals(1, attempts.get())
                presence.update(null)
            }
    }
}
