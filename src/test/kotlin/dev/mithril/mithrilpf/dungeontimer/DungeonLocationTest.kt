package dev.mithril.mithrilpf.dungeontimer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DungeonLocationTest {
    @Test
    fun `hypixel address works when brand is generic missing or changed`() {
        assertTrue(DungeonLocation.isHypixel("BungeeCord", "mc.hypixel.net:25565", false))
        assertTrue(DungeonLocation.isHypixel(null, "MC.HYPIXEL.NET.", false))
        assertTrue(DungeonLocation.isHypixel(null, "hypixel.net", false))
        assertTrue(DungeonLocation.isHypixel(null, "proxy.example", true))
        assertTrue(DungeonLocation.isHypixel("Hypixel BungeeCord", null, false))
        assertFalse(DungeonLocation.isHypixel("vanilla", "nothypixel.net", false))
        assertFalse(DungeonLocation.isHypixel(null, "hypixel.net.example.com", false))
        assertFalse(DungeonLocation.isHypixel(null, null, false))
    }

    @Test
    fun `raw prefix suffix work without synthetic score owner inserted`() {
        val prefix = "§7⏣ §cThe Catacombs "
        val suffix = "§7(§cF7§7)"
        val location = DungeonLocation()
        location.observe(listOf(prefix + suffix), "packet")
        assertEquals("F7", location.floor)
        assertTrue(location.inDungeon)
        assertEquals("packet", location.source)
    }

    @Test
    fun `temporary missing scoreboard never clears an active run location`() {
        val location = DungeonLocation()
        location.observe(listOf("The Catacombs (M7)"), "packet")
        location.observe(emptyList(), "scoreboard")
        location.observe(listOf("Cleared: 80%"), "scoreboard")
        assertEquals("M7", location.floor)
        assertTrue(location.inDungeon)
        location.reset()
        assertNull(location.floor)
        assertFalse(location.inDungeon)
    }

    @Test
    fun `location API enables dungeon only timer before floor is available`() {
        val location = DungeonLocation()
        location.location(true)
        assertTrue(location.inDungeon)
        assertNull(location.floor)
        location.observe(listOf("The Catacombs (F7)"), "packet")
        assertEquals("F7", location.floor)
        location.location(false)
        assertFalse(location.inDungeon)
        assertNull(location.floor)
        location.observe(listOf("The Catacombs (F7)"), "stale scoreboard")
        assertFalse(location.inDungeon)
        location.reset()
        location.observe(listOf("The Catacombs (M6)"), "packet")
        assertEquals("M6", location.floor)
    }

    @Test
    fun `floor lines tolerate nonbreaking spaces and zero width formatting`() {
        assertEquals("M7", DungeonTimerState.detectFloor(listOf("The Catacombs\u00a0\u200b( M7 )")))
        assertEquals("F7", DungeonTimerState.detectFloor(listOf("The Catacombs(F7)")))
        assertNull(DungeonTimerState.detectFloor(listOf("The Catacombs (F7) queue")))
    }
}
