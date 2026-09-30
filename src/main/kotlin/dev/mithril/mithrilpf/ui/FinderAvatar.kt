package dev.mithril.mithrilpf.ui

import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

/** Reuses skins already known to the game; an unknown portrait stays neutral. */
class FinderAvatar(uuid: String?) {
    private val id =
        uuid
            ?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
            ?.let {
                UUID.fromString(
                    "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}"
                )
            }

    fun draw(g: GuiGraphicsExtractor, x: Int, y: Int) {
        val skin = id?.let { Minecraft.getInstance().connection?.getPlayerInfo(it)?.skin }
        if (skin != null) PlayerFaceExtractor.extractRenderState(g, skin, x, y, 12)
        else {
            g.fill(x, y, x + 12, y + 12, Palette.SURFACE_RAISED)
            g.fill(x + 3, y + 2, x + 9, y + 8, Palette.MUTED)
            g.fill(x + 2, y + 9, x + 10, y + 12, Palette.MUTED)
        }
    }
}

class FinderAvatarWidget(uuid: String) : AbstractWidget(0, 0, 12, 12, Component.empty()) {
    private val avatar = FinderAvatar(uuid)

    init {
        active = false
    }

    override fun extractWidgetRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        avatar.draw(g, x, y)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        // Decorative preview; interactive controls provide their own narration.
    }
}
