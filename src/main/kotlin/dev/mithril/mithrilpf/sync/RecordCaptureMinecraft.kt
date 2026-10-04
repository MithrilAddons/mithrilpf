package dev.mithril.mithrilpf.sync

import java.util.UUID
import net.minecraft.client.User
import net.minecraft.client.multiplayer.PlayerInfo

/** Resolve from vanilla profiles, with the authenticated local identity taking precedence. */
internal fun RecordCapture.observePlayers(
    tab: Map<UUID, String>,
    players: Collection<PlayerInfo>?,
    user: User,
) {
    observeTab(
        tab.values,
        players.orEmpty().map { it.profile.name to it.profile.id } + (user.name to user.profileId),
    )
}
