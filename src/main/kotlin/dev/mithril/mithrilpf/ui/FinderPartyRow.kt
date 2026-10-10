package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.finder.DungeonRole
import dev.mithril.mithrilpf.finder.FinderMetric
import dev.mithril.mithrilpf.finder.FinderParty
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component

/** A stable selected row; hovering never chooses a reservation class. */
class FinderPartyRow(
    width: Int,
    private val party: FinderParty,
    private val selected: Boolean,
    private val eligible: Set<DungeonRole>,
    private val blocked: Set<DungeonRole>,
    action: () -> Unit,
) :
    Button(
        0,
        0,
        width,
        44,
        finderText("browse.row", party.leader, party.slots.count { it.filled }),
        { action() },
        DEFAULT_NARRATION,
    ) {
    private val avatar = FinderAvatar(party.leaderUuid)
    private val mainMetrics =
        listOf(FinderMetric.CATACOMBS, FinderMetric.S_PLUS, FinderMetric.POWER)
    private val extraRules =
        party.rules.shared.keys.count { it !in mainMetrics } +
            party.rules.perClass.values.sumOf { it.size } +
            party.rules.exempt.size

    init {
        val requirements =
            party.rules.shared.entries.joinToString(" · ") { (metric, value) ->
                finderText("metric.${metric.key}").string +
                    " " +
                    (if (metric.minimum) "≥" else "≤") +
                    metricText(metric, value.toDouble())
            }
        val lines =
            mutableListOf(
                averages().string,
                finderText(
                        "browse.shared_rules",
                        requirements.ifEmpty { finderText("browse.no_rules").string },
                    )
                    .string,
            )
        for ((role, rules) in party.rules.perClass) for ((metric, value) in rules) lines +=
            finderText("role.${role.key}").string +
                ": " +
                finderText("metric.${metric.key}").string +
                " " +
                (if (metric.minimum) "≥" else "≤") +
                metricText(metric, value.toDouble())
        if (party.rules.exempt.isNotEmpty())
            lines +=
                finderText("browse.exempt_roles", party.rules.exempt.joinToString("/") { it.short })
                    .string
        setTooltip(Tooltip.create(Component.literal(lines.joinToString("\n"))))
    }

    private fun averages() =
        finderText(
            "browse.averages",
            metricText(FinderMetric.CATACOMBS, party.averageCata),
            metricText(FinderMetric.S_PLUS, party.averageTime),
        )

    override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val font = Minecraft.getInstance().font
        val border = if (selected || isFocused) Palette.ACCENT else Palette.BORDER
        g.fill(x, y, right, bottom, border)
        g.fill(
            x + 1,
            y + 1,
            right - 1,
            bottom - 1,
            if (isHoveredOrFocused || selected) Palette.SURFACE_RAISED else Palette.SURFACE,
        )
        fun line(text: Component, lx: Int, ly: Int, max: Int, color: Int) {
            g.text(
                font,
                Language.getInstance()
                    .getVisualOrder(font.substrByWidth(text, max.coerceAtLeast(0))),
                lx,
                ly,
                color,
                false,
            )
        }
        val slotX = right - 78
        avatar.draw(g, x + 6, y + 3)
        line(
            message,
            x + 23,
            y + 5,
            width - 105,
            Palette.TEXT,
        )
        for ((index, slot) in party.slots.withIndex()) {
            val sx = slotX + index * 14
            FinderSprites.role(
                g,
                slot.role,
                when {
                    slot.filled -> 0
                    slot.role in blocked -> 4
                    slot.role in eligible -> 2
                    else -> 1
                },
                sx,
                y + 4,
            )
        }
        val rules = party.rules.shared
        val extra = if (extraRules == 0) null else finderText("browse.extra_rules", extraRules)
        val extraWidth = extra?.let { font.width(it) + 8 } ?: 0
        line(averages(), x + 6, y + 18, width - 12 - extraWidth, Palette.MUTED)
        if (extra != null) line(extra, right - extraWidth, y + 18, extraWidth - 6, Palette.ACCENT)
        val info =
            mainMetrics.joinToString(" · ") { metric ->
                finderText("short_metric.${metric.key}").string +
                    " " +
                    (rules[metric]?.let {
                        (if (metric.minimum) "≥" else "≤") + metricText(metric, it.toDouble())
                    } ?: "—")
            }
        line(finderText("browse.shared_rules", info), x + 6, y + 31, width - 12, Palette.TEXT)
    }
}
