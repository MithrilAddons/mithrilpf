package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import kotlin.test.*

class GameTransportTest {
    @Test
    fun `only Curator routes are reachable, reads without a body`() {
        for (path in
            listOf(
                "games/curator/today",
                "games/curator/leaderboard",
                "games/curator/catalog",
                "games/curator/catalog?version=3%3A1791302445914",
            )) LinkTransport.validateGamePath(path, null)
        LinkTransport.validateGamePath("games/curator/guess", JsonObject())
        for ((path, body) in
            listOf(
                "games/curator/guess" to null,
                "games/curator/today" to JsonObject(),
                "games/curator/catalog?version=../x" to null,
                "games/other/today" to null,
                "auth/device-session" to null,
            )) assertFails { LinkTransport.validateGamePath(path, body) }
        assertFails { LinkTransport.gameRequest("games/curator/today", null, "short") }
    }

    @Test
    fun `server explanations are read as plain text only`() {
        assertEquals(
            "A new item is ready",
            LinkTransport.detail("""{"detail":"A new item is ready"}"""),
        )
        assertNull(LinkTransport.detail("""{"detail":{"code":"x"}}"""))
        assertNull(LinkTransport.detail("broken"))
        assertEquals(200, LinkTransport.detail("""{"detail":"${"a".repeat(500)}"}""")!!.length)
    }
}
