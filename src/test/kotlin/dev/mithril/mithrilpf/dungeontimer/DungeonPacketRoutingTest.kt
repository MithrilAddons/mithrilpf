package dev.mithril.mithrilpf.dungeontimer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard

class DungeonPacketRoutingTest {
    @Test
    fun `bundled pings are excluded without losing chat or scoreboard updates`() {
        val ping = ClientboundPingPacket(42)
        val chat = ClientboundSystemChatPacket(Component.literal(DungeonTimerState.START), false)
        val team =
            ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(
                PlayerTeam(Scoreboard(), "test"),
                true,
            )
        val bundle =
            ClientboundBundlePacket(listOf<Packet<in ClientGamePacketListener>>(ping, chat, team))
        val visited = mutableListOf<Pair<Packet<*>, Boolean>>()
        val counter = HypixelTickCounter()
        var ticks = 0
        visitDungeonPackets(bundle) { packet, bundled ->
            visited.add(packet to bundled)
            if (packet is ClientboundPingPacket && counter.accept(packet.id, bundled)) ticks++
        }
        visitDungeonPackets(ping) { packet, bundled ->
            if (packet is ClientboundPingPacket && counter.accept(packet.id, bundled)) ticks++
        }
        assertEquals(3, visited.size)
        assertEquals(listOf(true, true, true), visited.map { it.second })
        assertSame(chat, visited[1].first)
        assertSame(team, visited[2].first)
        assertEquals(1, ticks)
        assertEquals(1L, counter.ignoredBundled)
    }

    @Test
    fun `nested bundles preserve bundled origin`() {
        val ping = ClientboundPingPacket(10)
        val inner = ClientboundBundlePacket(listOf(ping))
        val outer = ClientboundBundlePacket(listOf(inner))
        val origins = mutableListOf<Boolean>()
        visitDungeonPackets(outer) { packet, bundled ->
            assertSame(ping, packet)
            origins.add(bundled)
        }
        assertEquals(listOf(true), origins)
    }
}
