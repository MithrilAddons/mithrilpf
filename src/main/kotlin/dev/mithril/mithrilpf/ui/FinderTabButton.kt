package dev.mithril.mithrilpf.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

class FinderTabButton(
    width: Int,
    label: Component,
    private val selected: Boolean,
    action: () -> Unit,
) : Button(0, 0, width, 22, label, { action() }, DEFAULT_NARRATION) {
    override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (isHoveredOrFocused) g.fill(x, y, x + width, y + height, Palette.SURFACE)
        if (selected || isFocused) g.fill(x, y + height - 1, x + width, y + height, Palette.ACCENT)
        val font = Minecraft.getInstance().font
        g.text(
            font,
            message,
            x + (width - font.width(message)) / 2,
            y + 7,
            if (selected || isHoveredOrFocused) Palette.TEXT else Palette.MUTED,
            false,
        )
    }
}
