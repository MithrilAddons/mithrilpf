package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.MithrilPF
import dev.mithril.mithrilpf.dungeontimer.DungeonTimers
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

object FinderHud {
    fun register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("mithrilpf", "finder")) { g, _ ->
            val client = Minecraft.getInstance()
            if (
                client.level == null ||
                    client.screen != null ||
                    client.options.hideGui ||
                    !DungeonTimers.settings.finderHud
            )
                return@addLast
            val state = MithrilPF.finder.state ?: return@addLast
            val message =
                state.looking?.let {
                    finderText(
                        "hud.looking",
                        it.floor,
                        it.roles.joinToString("/") { role -> role.short },
                    )
                }
                    ?: state.party
                        ?.takeUnless { it.completed }
                        ?.let {
                            finderText(
                                "hud.party",
                                it.floor,
                                it.slots.count { slot -> slot.filled },
                            )
                        }
                    ?: return@addLast
            val width = client.font.width(message) + 12
            val x = (client.window.guiScaledWidth - width) / 2
            g.fill(x, 6, x + width, 24, Palette.BACKGROUND)
            g.text(client.font, message, x + 6, 11, Palette.ACCENT, false)
        }
    }
}
