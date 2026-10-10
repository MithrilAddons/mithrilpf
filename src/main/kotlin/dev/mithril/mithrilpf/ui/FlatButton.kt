package dev.mithril.mithrilpf.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText

class FlatButton(
    x: Int,
    y: Int,
    width: Int,
    label: Component,
    private val primary: Boolean = false,
    action: () -> Unit,
) : Button(x, y, width, 20, label, { action() }, DEFAULT_NARRATION) {
    /** Destructive actions (such as skipping an update) use danger-coloured text. */
    var danger = false

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
        val label = ellipsize(message.string, (width - 10).coerceAtLeast(0), font::width)
        val text = Language.getInstance().getVisualOrder(FormattedText.of(label))
        g.text(
            font,
            text,
            x + (width - font.width(text)) / 2,
            y + (height - 9) / 2,
            if (danger && active && !primary) Palette.DANGER else colors.text,
            false,
        )
    }
}
