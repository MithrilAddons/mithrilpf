package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.finder.DungeonRole
import dev.mithril.mithrilpf.finder.FinderMetric
import dev.mithril.mithrilpf.finder.FinderStats
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component

/** Reads the current draft so typing updates the preview without rebuilding focused inputs. */
class FinderListingPreview(
    width: Int,
    private val draft: FinderDraft,
    private val name: String,
    private val stats: FinderStats?,
) : AbstractWidget(0, 0, width, 180, finderText("editor.preview")) {
    init {
        active = false
    }

    override fun extractWidgetRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        val font = Minecraft.getInstance().font
        g.fill(x, y, right, bottom, Palette.BORDER)
        g.fill(x + 1, y + 1, right - 1, bottom - 1, Palette.SURFACE)
        fun text(value: Component, dy: Int, color: Int = Palette.TEXT) {
            g.text(
                font,
                Language.getInstance().getVisualOrder(font.substrByWidth(value, width - 16)),
                x + 8,
                y + dy,
                color,
                false,
            )
        }
        text(message, 8, Palette.MUTED)
        text(Component.literal(name), 26)
        text(
            finderText(
                "editor.preview_meta",
                draft.floor,
                metricText(FinderMetric.CATACOMBS, stats?.shared?.get(FinderMetric.CATACOMBS)),
            ),
            39,
            Palette.MUTED,
        )
        val slots = if (draft.duplicates) draft.slots else DungeonRole.entries
        val occupied = slots.indexOf(draft.leader)
        for ((index, role) in slots.withIndex()) FinderSprites.role(
            g,
            role,
            if (index == occupied) 3 else 1,
            x + 8 + index * 17,
            y + 54,
        )
        var dy = 76
        for (metric in FinderMetric.entries) {
            val raw = draft.shared[metric].orEmpty()
            if (raw.isBlank()) continue
            val parsed = runCatching { metricInput(metric, raw) }
            val value = parsed.getOrNull()?.let { inputValue(metric, it) } ?: "?"
            text(
                finderText(
                    "editor.preview_rule",
                    finderText("metric.${metric.key}"),
                    (if (metric.minimum) "≥" else "≤") + value,
                ),
                dy,
                if (parsed.isFailure) Palette.DANGER else Palette.TEXT,
            )
            dy += 12
        }
        if (dy == 76) {
            text(finderText("browse.no_rules"), dy, Palette.MUTED)
            dy += 12
        }
        val extra =
            draft.perClass.values.sumOf { rules -> rules.values.count { it.isNotBlank() } } +
                draft.exempt.size
        if (extra > 0) text(finderText("editor.preview_classes", extra), dy, Palette.ACCENT)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        // Decorative preview; interactive controls provide their own narration.
    }
}
