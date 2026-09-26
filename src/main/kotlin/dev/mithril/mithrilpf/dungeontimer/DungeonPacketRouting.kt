package dev.mithril.mithrilpf.dungeontimer

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBundlePacket

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
