package dev.mithril.mithrilpf.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component

class FlatButton(
    x: Int,
    y: Int,
    width: Int,
    label: Component,
    private val primary: Boolean = false,
    action: () -> Unit,
) : Button(x, y, width, 20, label, { action() }, DEFAULT_NARRATION) {
    override fun extractContents(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        val colors = Palette.button(primary, active, isHoveredOrFocused)
        g.fill(x, y, x + width, y + height, colors.border)
        g.fill(x + 1, y + 1, x + width - 1, y + height - 1, colors.background)
        val font = Minecraft.getInstance().font
        val text =
            Language.getInstance()
                .getVisualOrder(font.substrByWidth(message, (width - 10).coerceAtLeast(0)))
        g.text(
            font,
            text,
            x + (width - font.width(text)) / 2,
            y + (height - 9) / 2,
            colors.text,
            false,
        )
    }
}
