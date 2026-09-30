package dev.mithril.mithrilpf.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.network.chat.Component

/** The entire row is a keyboard-accessible switch; descriptions wrap in the active game font. */
class SettingToggle(
    width: Int,
    private val label: Component,
    private val description: Component,
    private val value: () -> Boolean,
    private val available: () -> Boolean,
    change: (Boolean) -> Unit,
) : Button(0, 0, width, 20, label, { change(!value()) }, DEFAULT_NARRATION) {
    private val font = Minecraft.getInstance().font
    private val stateWidth =
        maxOf(
            font.width(Component.translatable("options.on")),
            font.width(Component.translatable("options.off")),
        )
    private val labelLines = font.split(label, (width - stateWidth - 44).coerceAtLeast(1))
    private val descriptionLines = font.split(description, (width - 16).coerceAtLeast(1))
    private var selected = value()
    private var stateText: Component = Component.empty()

    init {
        height = 14 + (labelLines.size + descriptionLines.size) * font.lineHeight
        refresh()
    }

    fun refresh() {
        selected = value()
        active = available()
        stateText = Component.translatable(if (selected) "options.on" else "options.off")
        message = Component.translatable("tracking.mithrilpf.toggle", label, stateText)
    }

    override fun createNarrationMessage() = message.copy().append(". ").append(description)

    override fun onPress(input: InputWithModifiers) {
        super.onPress(input)
        refresh()
    }

    override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val colors = Palette.button(false, active, isHoveredOrFocused)
        g.fill(x, y, right, bottom, colors.border)
        g.fill(x + 1, y + 1, right - 1, bottom - 1, colors.background)
        var lineY = y + 6
        for (line in labelLines) {
            g.text(font, line, x + 8, lineY, colors.text, false)
            lineY += font.lineHeight
        }
        lineY += 2
        for (line in descriptionLines) {
            g.text(font, line, x + 8, lineY, Palette.MUTED, false)
            lineY += font.lineHeight
        }
        val stateColor = if (active && selected) Palette.ACCENT else Palette.MUTED
        g.text(font, stateText, right - 28 - font.width(stateText), y + 6, stateColor, false)
        g.fill(right - 24, y + 6, right - 8, y + 14, stateColor)
        g.fill(right - 23, y + 7, right - 9, y + 13, Palette.BACKGROUND)
        val knob = if (selected) right - 15 else right - 22
        g.fill(knob, y + 8, knob + 5, y + 12, stateColor)
    }
}
