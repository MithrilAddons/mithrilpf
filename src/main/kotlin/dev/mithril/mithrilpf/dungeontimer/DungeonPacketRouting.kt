package dev.mithril.mithrilpf.dungeontimer

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket

/** Both vanilla action-bar packet forms carry secret counters; ordinary chat never does. */
internal fun dungeonActionBar(packet: Packet<*>): String? =
    when (packet) {
        is ClientboundSetActionBarTextPacket -> packet.text.string
        is ClientboundSystemChatPacket -> if (packet.overlay()) packet.content().string else null
        else -> null
    }?.let(DungeonTimerState::clean)

/** Preserve packet origin while still delivering bundled chat and scoreboard updates. */
internal fun visitDungeonPackets(
    packet: Packet<*>,
    bundled: Boolean = false,
    visitor: (Packet<*>, Boolean) -> Unit,
) {
    if (packet is ClientboundBundlePacket) {
        packet.subPackets().forEach { visitDungeonPackets(it, true, visitor) }
    } else {
        visitor(packet, bundled)
    }
}
