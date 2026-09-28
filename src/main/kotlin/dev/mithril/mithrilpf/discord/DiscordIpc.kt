package dev.mithril.mithrilpf.discord

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path

/** Discord's local RPC framing, not the authenticated Discord HTTP/Gateway API. */
class DiscordIpc(
    private val input: InputStream,
    private val output: OutputStream,
    private val release: () -> Unit,
) : AutoCloseable {
    fun handshake() {
        write(
            0,
            JsonObject().apply {
                addProperty("v", 1)
                addProperty("client_id", APPLICATION_ID)
            },
        )
        val reply = receive()
        require(reply.get("evt")?.asString == "READY") { "Discord did not become ready" }
    }

    fun activity(value: DiscordActivity, nonce: String) {
        write(
            1,
            JsonObject().apply {
                addProperty("cmd", "SET_ACTIVITY")
                addProperty("nonce", nonce)
                add(
                    "args",
                    JsonObject().apply {
                        addProperty("pid", ProcessHandle.current().pid())
                        add("activity", value.json())
                    },
                )
            },
        )
        // Ignore unrelated dispatches, but keep the entire exchange bounded by the caller.
        repeat(32) {
            val reply = receive()
            if (reply.get("nonce")?.takeUnless { it.isJsonNull }?.asString == nonce) {
                require(reply.get("evt")?.takeUnless { it.isJsonNull }?.asString != "ERROR")
                require(reply.get("cmd")?.asString == "SET_ACTIVITY")
                return
            }
        }
        throw IOException("Too many unrelated Discord messages")
    }

    private fun receive(): JsonObject {
        repeat(32) {
            val header = ByteBuffer.wrap(exact(8)).order(ByteOrder.LITTLE_ENDIAN)
            val opcode = header.int
            val length = header.int
            require(length in 0..65536) { "Invalid Discord frame size" }
            val body = JsonParser.parseString(exact(length).toString(Charsets.UTF_8))
            when (opcode) {
                1 -> return body.asJsonObject
                3 -> write(4, body)
                else -> throw IOException("Discord closed or sent an unexpected frame")
            }
        }
        throw IOException("Too many Discord pings")
    }

    private fun exact(size: Int): ByteArray =
        input.readNBytes(size).also {
            if (it.size != size) throw EOFException("Discord disconnected")
        }

    private fun write(opcode: Int, body: JsonElement) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65536)
        output.write(
            ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(opcode)
                .putInt(bytes.size)
                .array()
        )
        output.write(bytes)
        output.flush()
    }

    override fun close() = release()

    companion object {
        const val APPLICATION_ID = "1553815052237152407"

        internal fun windowsPipePath(index: Int) = "\\\\.\\pipe\\discord-ipc-$index"

        /** Only fixed local IPC endpoints. No TCP, shell, token or registry access. */
        fun connect(): DiscordIpc {
            val windows = System.getProperty("os.name").startsWith("Windows")
            val directory =
                listOf("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP").firstNotNullOfOrNull {
                    System.getenv(it)?.takeIf(String::isNotBlank)
                } ?: "/tmp"
            for (index in 0..9) {
                try {
                    if (windows) {
                        val file = RandomAccessFile(windowsPipePath(index), "rw")
                        return DiscordIpc(
                            object : InputStream() {
                                override fun read() = file.read()

                                override fun read(bytes: ByteArray, offset: Int, length: Int) =
                                    file.read(bytes, offset, length)
                            },
                            object : OutputStream() {
                                override fun write(value: Int) = file.write(value)

                                override fun write(bytes: ByteArray, offset: Int, length: Int) =
                                    file.write(bytes, offset, length)
                            },
                            file::close,
                        )
                    }
                    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                    try {
                        channel.connect(
                            UnixDomainSocketAddress.of(Path.of(directory, "discord-ipc-$index"))
                        )
                        return DiscordIpc(
                            Channels.newInputStream(channel),
                            Channels.newOutputStream(channel),
                            channel::close,
                        )
                    } catch (failure: Exception) {
                        channel.close()
                        throw failure
                    }
                } catch (_: IOException) {
                    // Discord may be closed or running in another local IPC slot.
                }
            }
            throw IOException("Discord is not available")
        }
    }
}
