package dev.mithril.mithrilpf.games

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.mithril.mithrilpf.account.ServiceFailure
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CuratorClientTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun contract(part: String): String =
        JsonParser.parseString(javaClass.getResource("/contracts/curator-v1.json")!!.readText())
            .asJsonObject
            .get(part)
            .toString()

    private class Host : GamesHost {
        override var uuid = "fedcba9876543210fedcba9876543210"
        override var token: String? = "t".repeat(43)
        var clock = 1_800_000_000L
        val callbacks = LinkedBlockingQueue<() -> Unit>()

        override fun execute(action: () -> Unit) {
            callbacks.add(action)
        }

        override fun now() = clock

        fun apply(count: Int = 1) {
            repeat(count) {
                assertNotNull(callbacks.poll(5, TimeUnit.SECONDS), "worker completion")()
            }
        }
    }

    private class Server {
        val responses = mutableMapOf<String, () -> String>()
        val calls = LinkedBlockingQueue<Pair<String, JsonObject?>>()

        fun request(path: String, body: JsonObject?, token: String): String {
            calls.add(path to body)
            return responses[path.substringBefore('?')]?.invoke() ?: error("no response for $path")
        }
    }

    private val catalog =
        """{"version":1,"catalog":"3:1","items":[["SYNTHESIZER_V3","Synthesizer v3"]]}"""

    private fun client(host: Host, server: Server) =
        CuratorClient(
            host,
            CuratorCatalogStore(temporary.root.toPath().resolve("curator.json")),
            server::request,
        )

    private fun ready(host: Host, server: Server): CuratorClient {
        server.responses["games/curator/catalog"] = { catalog }
        server.responses["games/curator/today"] = { contract("playing") }
        val games = client(host, server)
        games.tick(visible = true)
        host.apply(2)
        return games
    }

    @Test
    fun `opening the screen loads the item list and today's round`() {
        val host = Host()
        val server = Server()
        val games = ready(host, server)
        assertEquals("Synthesizer v3", games.catalog!!.items.single().name)
        assertEquals(RoundState.PLAYING, games.today!!.state)
        assertTrue(Files.exists(temporary.root.toPath().resolve("curator.json")))
        games.tick(visible = true)
        assertTrue(host.callbacks.isEmpty())
        games.close()
    }

    @Test
    fun `a cached item list is only downloaded again when it changed`() {
        val host = Host()
        val server = Server()
        ready(host, server).close()
        server.calls.clear()
        server.responses["games/curator/catalog"] = {
            """{"version":1,"catalog":"3:1","unchanged":true}"""
        }
        val games = client(host, server)
        games.tick(visible = true)
        host.apply(2)
        assertEquals("games/curator/catalog?version=3%3A1", server.calls.first().first)
        assertEquals(1, games.catalog!!.items.size)
        games.close()
    }

    @Test
    fun `failed loads wait before retrying`() {
        val host = Host()
        val server = Server()
        server.responses["games/curator/catalog"] = { throw ServiceFailure(503) }
        server.responses["games/curator/today"] = { throw ServiceFailure(503) }
        val games = client(host, server)
        games.tick(visible = true)
        host.apply(2)
        assertEquals("unavailable", games.error)
        repeat(3) { games.tick(visible = true) }
        assertTrue(host.callbacks.isEmpty())
        assertEquals(2, server.calls.size)
        host.clock += CuratorClient.RETRY
        games.tick(visible = true)
        host.apply(2)
        assertEquals(4, server.calls.size)
        games.close()
    }

    @Test
    fun `nothing loads while hidden or signed out`() {
        val host = Host()
        val server = Server()
        val games = client(host, server)
        games.tick(visible = false)
        host.token = null
        games.tick(visible = true)
        assertFalse(games.signedIn)
        assertTrue(server.calls.isEmpty())
        games.close()
    }

    @Test
    fun `a guess replaces the round with the backend's answer`() {
        val host = Host()
        val server = Server()
        val games = ready(host, server)
        server.responses["games/curator/guess"] = { contract("solved") }
        games.guess(CatalogItem("SYNTHESIZER_V2", "Synthesizer v2"))
        assertEquals("already_guessed", games.error)
        games.guess(CatalogItem("SYNTHESIZER_V3", "Synthesizer v3"))
        assertTrue(games.guessing)
        games.guess(CatalogItem("HYPERION", "Hyperion"))
        host.apply()
        assertFalse(games.guessing)
        assertEquals(RoundState.SOLVED, games.today!!.state)
        assertNull(games.error)
        val (path, body) = server.calls.last()
        assertEquals("games/curator/guess", path)
        assertEquals("SYNTHESIZER_V3", body!!.get("item").asString)
        games.guess(CatalogItem("HYPERION", "Hyperion"))
        assertEquals(1, server.calls.count { it.first == "games/curator/guess" })
        games.close()
    }

    @Test
    fun `server rejections become known messages`() {
        val host = Host()
        val server = Server()
        val games = ready(host, server)
        val cases =
            listOf(
                ServiceFailure(409, null, CuratorClient.NEW_DAY) to "new_day",
                ServiceFailure(404, null, "Unknown item") to "unknown_item",
                ServiceFailure(409, null, "untrusted text") to "unavailable",
                ServiceFailure(401) to "signed_out",
                ServiceFailure(403, "banned") to "banned",
                IllegalStateException("offline") to "unavailable",
            )
        for ((failure, key) in cases) {
            server.responses["games/curator/guess"] = { throw failure }
            games.guess(CatalogItem("HYPERION", "Hyperion"))
            host.apply()
            assertEquals(key, games.error)
            assertFalse(games.guessing)
        }
        assertTrue(games.newDay)
        games.close()
    }

    @Test
    fun `the new day is noticed at the reset and loaded on request`() {
        val host = Host()
        val server = Server()
        val games = ready(host, server)
        host.clock = games.today!!.resetsAt
        games.tick(visible = true)
        assertTrue(games.newDay)
        games.reload()
        assertNull(games.today)
        assertFalse(games.newDay)
        server.responses["games/curator/today"] = {
            """{"version":1,"day":"2027-01-16","state":"preparing","resets_at":1800144000}"""
        }
        games.tick(visible = true)
        host.apply()
        assertEquals(RoundState.PREPARING, games.today!!.state)
        games.tick(visible = true)
        assertTrue(host.callbacks.isEmpty())
        host.clock += CuratorClient.RETRY
        games.tick(visible = true)
        host.apply()
        games.close()
    }

    @Test
    fun `an open leaderboard picks up other players' results`() {
        val host = Host()
        val server = Server()
        val games = ready(host, server)
        fun loads() = server.calls.count { it.first == "games/curator/leaderboard" }
        server.responses["games/curator/leaderboard"] = { contract("leaderboard") }
        games.tick(visible = true)
        assertEquals(0, loads())
        games.boardOpen = true
        games.tick(visible = true)
        games.tick(visible = true)
        host.apply()
        assertEquals("2027-01", games.board!!.season)
        assertEquals(1, loads())
        // Someone else finishing changes the standings; an open view notices within a minute.
        host.clock += CuratorClient.BOARD_REFRESH - 1
        games.tick(visible = true)
        assertTrue(host.callbacks.isEmpty())
        host.clock += 1
        games.tick(visible = true)
        host.apply()
        assertEquals(2, loads())
        // Reopening the screen, or the player's own guess, fetches it straight away.
        games.refreshBoard()
        games.tick(visible = true)
        host.apply()
        assertEquals(3, loads())
        server.responses["games/curator/guess"] = { contract("solved") }
        games.guess(CatalogItem("SYNTHESIZER_V3", "Synthesizer v3"))
        host.apply()
        games.tick(visible = true)
        host.apply()
        assertEquals(4, loads())
        assertNotNull(games.board)
        server.responses["games/curator/leaderboard"] = { throw ServiceFailure(503) }
        games.refreshBoard()
        games.tick(visible = true)
        host.apply()
        assertEquals("unavailable", games.error)
        assertNotNull(games.board)
        games.tick(visible = true)
        assertEquals(5, loads())
        games.boardOpen = false
        host.clock += CuratorClient.BOARD_REFRESH
        games.tick(visible = true)
        assertEquals(5, loads())
        games.close()
    }

    @Test
    fun `switching accounts drops the previous player's round`() {
        val host = Host()
        val server = Server()
        val games = ready(host, server)
        server.responses["games/curator/guess"] = { contract("solved") }
        games.guess(CatalogItem("SYNTHESIZER_V3", "Synthesizer v3"))
        host.uuid = "0123456789abcdef0123456789abcdef"
        games.tick(visible = false)
        assertNull(games.today)
        host.apply()
        assertNull(games.today)
        assertFalse(games.guessing)
        games.close()
    }
}
