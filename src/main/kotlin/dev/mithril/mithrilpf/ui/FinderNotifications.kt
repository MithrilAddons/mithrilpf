package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.dungeontimer.DungeonTimers
import dev.mithril.mithrilpf.finder.FinderNotice
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

internal fun finderNotices(client: Minecraft, notices: List<FinderNotice>) {
    for (notice in notices) {
        if (
            notice.kind !in
                setOf(
                    "placed",
                    "reserved",
                    "party_full",
                    "party_closed",
                    "left_party",
                    "leader_now",
                    "party_joined",
                    "stopped_looking",
                )
        )
            continue
        client.player?.sendSystemMessage(
            Component.translatable("finder.mithrilpf.notice.${notice.kind}").withStyle {
                it.withColor(Palette.ACCENT and 0xFFFFFF)
                    .withClickEvent(ClickEvent.RunCommand("/mpf"))
            }
        )
        if (
            client.player != null &&
                DungeonTimers.settings.finderSound &&
                notice.kind in setOf("placed", "party_full")
        )
            client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.0f))
    }
}
