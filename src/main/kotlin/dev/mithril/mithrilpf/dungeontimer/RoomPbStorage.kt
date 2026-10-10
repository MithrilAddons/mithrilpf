package dev.mithril.mithrilpf.dungeontimer

import dev.mithril.mithrilpf.soloroom.RoomPbGate
import net.minecraft.network.chat.Component

/** Room PBs of whoever is playing, on the floor they're on. Client-thread owned. */
class RoomPbStorage(
    private val storage: () -> TrackingStorage,
    private val player: () -> String?,
    private val floor: () -> String?,
    private val chat: (Component) -> Unit,
) : RoomPbGate.Host {
    override fun best() = storage().records["rooms"]?.get(player())?.get(floor()).orEmpty()

    override fun save(times: Map<String, SplitTime>, saved: (Int) -> Unit) {
        val player = player() ?: return
        val floor = floor() ?: return
        storage().record("rooms", player, floor, times) { _, changed ->
            // Nothing is reported to an account that wasn't the one playing.
            if (player() == player) saved(changed.size)
        }
    }

    override fun send(line: Component) = chat(line)
}
