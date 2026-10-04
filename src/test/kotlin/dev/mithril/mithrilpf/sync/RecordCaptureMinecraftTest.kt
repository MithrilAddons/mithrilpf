package dev.mithril.mithrilpf.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import dev.mithril.mithrilpf.dungeontimer.SplitTime
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import java.util.Optional
import java.util.UUID
import kotlin.test.*
import net.minecraft.client.User
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component

class RecordCaptureMinecraftTest {
    private val self = UUID.randomUUID()
    private val user = User("Player", self, "synthetic-test", Optional.empty(), Optional.empty())
    private val line = "[30] Player (Mage XX)"
    private val fake = UUID.fromString("11234567-89ab-2def-8123-456789abcdef")

    @Test
    fun `solo capture uses signed in identity when the only displayed player has a synthetic UUID`() {
        val entry = PlayerInfo(GameProfile(fake, "Player"), false)
        entry.tabListDisplayName = Component.literal(line)
        val capture = RecordCapture()
        capture.observePlayers(
            mapOf(fake to entry.tabListDisplayName!!.string),
            listOf(entry),
            user,
        )
        capture.begin("F7", TimerStamp(0, 0), 123456, true, false)
        assertNotNull(capture.poll())
        capture.progress(TimerStamp(100, 5_000_000_000), false, "tracking", JsonObject())
        assertEquals(listOf(id(self)), roster(capture))
    }

    @Test
    fun `local account overrides server profile and teammates use profile names not display text`() {
        val teammate = UUID.randomUUID()
        val capture = RecordCapture()
        val lines = listOf(line, "[30] Teammate (Tank XX)")
        val players =
            listOf(
                PlayerInfo(GameProfile(UUID.randomUUID(), "Player"), false),
                PlayerInfo(GameProfile(teammate, "Teammate"), false),
                PlayerInfo(GameProfile(fake, "Teammate"), false),
            )
        capture.observePlayers(lines.associateBy { UUID.randomUUID() }, players, user)
        capture.begin("M7", TimerStamp(0, 0), 123456, false, false)
        capture.terminal("M7", SplitTime(40000, 800), TimerStamp(1200, 60_000_000_000))
        assertEquals(listOf(self, teammate).map(::id).sorted(), roster(capture))
    }

    @Test
    fun `absent player list still resolves self but never invents a teammate identity`() {
        val capture = RecordCapture()
        capture.observePlayers(mapOf(fake to line), null, user)
        capture.begin("M7", TimerStamp(0, 0), 123456, false, false)
        capture.terminal("M7", SplitTime(40000, 800), TimerStamp(1200, 60_000_000_000))
        assertEquals(listOf(id(self)), roster(capture))
        capture.observePlayers(mapOf(fake to "[30] Teammate (Tank XX)"), emptyList(), user)
        capture.terminal("M7", SplitTime(40000, 800), TimerStamp(1200, 60_000_000_000))
        assertNull(capture.poll())
    }

    private fun id(uuid: UUID) = uuid.toString().replace("-", "")

    private fun roster(capture: RecordCapture): List<String> =
        JsonParser.parseString(assertNotNull(capture.poll()).body)
            .asJsonObject["roster"]
            .asJsonArray
            .map { it.asString }
}
