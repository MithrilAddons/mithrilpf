package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.finder.DungeonRole
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

object FinderSprites {
    private val texture = Identifier.fromNamespaceAndPath("mithrilpf", "textures/gui/finder.png")

    fun role(g: GuiGraphicsExtractor, role: DungeonRole, state: Int, x: Int, y: Int) {
        g.blit(
            RenderPipelines.GUI_TEXTURED,
            texture,
            x,
            y,
            role.ordinal * 11f,
            state * 11f,
            11,
            11,
            66,
            77,
        )
    }
}

class FinderRoleButton(
    width: Int,
    private val role: DungeonRole,
    private val selected: Boolean,
    action: () -> Unit,
) : Button(0, 0, width, 20, finderText("role.${role.key}"), { action() }, DEFAULT_NARRATION) {
    init {
        setTooltip(Tooltip.create(message))
    }

    override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // Locked choices (such as your class while editing a party) never look clickable.
        val highlighted = active && isHoveredOrFocused
        g.fill(x, y, right, bottom, if (highlighted) Palette.ACCENT else Palette.BORDER)
        g.fill(
            x + 1,
            y + 1,
            right - 1,
            bottom - 1,
            if (selected) Palette.SURFACE_RAISED else Palette.BACKGROUND,
        )
        FinderSprites.role(g, role, if (selected) 3 else 1, x + (width - 11) / 2, y + 4)
    }
}
