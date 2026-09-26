package dev.mithril.mithrilpf.dungeontimer

import dev.mithril.mithrilpf.ui.Palette
import dev.mithril.mithrilpf.ui.TrackingHudEditor
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/** Formatted snapshots are built once per tick, never by scanning the world from rendering. */
object DungeonTimerHud {
    enum class Kind {
        TABLE,
        SPLIT,
        TICKS,
    }

    data class Line(
        val label: String,
        val value: Component = Component.empty(),
        val color: Int = Palette.ACCENT,
        val footer: Boolean = false,
    )

    private var snapshots: Map<Kind, List<Line>> = emptyMap()

    fun position(kind: Kind, settings: TrackingSettings = DungeonTimers.settings) =
        when (kind) {
            Kind.TABLE -> settings.tablePosition
            Kind.SPLIT -> settings.splitPosition
            Kind.TICKS -> settings.tickPosition
        }

    fun enabled(kind: Kind) =
        with(DungeonTimers.settings) {
            when (kind) {
                Kind.TABLE -> table
                Kind.SPLIT -> split
                Kind.TICKS -> ticks
            }
        }

    fun move(settings: TrackingSettings, kind: Kind, position: HudPosition) =
        when (kind) {
            Kind.TABLE -> settings.copy(tablePosition = position)
            Kind.SPLIT -> settings.copy(splitPosition = position)
            Kind.TICKS -> settings.copy(tickPosition = position)
        }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!DungeonTimers.settings.enabled || client.level == null) {
                snapshots = emptyMap()
                return@register
            }
            val now = DungeonTimers.now()
            val state = DungeonTimers.state
            val rows = state?.rows(now).orEmpty()
            val table =
                if (state == null || rows.isEmpty()) emptyList()
                else {
                    listOf(Line(state.floor)) +
                        rows.entries
                            .sortedBy {
                                when (it.key) {
                                    "Boss Entry" -> 1
                                    "Boss" -> 2
                                    "Total" -> 3
                                    else -> 0
                                }
                            }
                            .map { (name, time) ->
                                Line(
                                    DungeonTimers.splitName(name),
                                    DungeonTimeFormat.styledPair(
                                        time,
                                        DungeonPbColor.color(
                                            name,
                                            time.ticks,
                                            DungeonTimers.bestTicks(state.floor, name),
                                        ),
                                    ),
                                )
                            } +
                        listOf(
                            Line(
                                DungeonTimers.message("estimate").string,
                                Component.literal(
                                    DungeonTimers.estimate(state, now)?.let {
                                        DungeonTimeFormat.millis(it.realMillis)
                                    } ?: "—"
                                ),
                                footer = true,
                            ),
                            Line(
                                DungeonTimers.message("lag").string,
                                Component.literal(
                                    DungeonTimeFormat.millis(
                                        rows["Total"]?.let {
                                            (it.realMillis - it.ticks * 50).coerceAtLeast(0)
                                        } ?: 0
                                    )
                                ),
                                DungeonPbColor.RED,
                                true,
                            ),
                        )
                }
            val current = state?.takeUnless { it.ended }?.current(now)
            val value = DungeonTimerState.countdown(now.ticks)
            snapshots =
                mapOf(
                    Kind.TABLE to table,
                    Kind.SPLIT to
                        current
                            ?.let { (name, time) ->
                                listOf(
                                    Line(
                                        DungeonTimeFormat.ticks(time.ticks),
                                        color =
                                            DungeonPbColor.color(
                                                name,
                                                time.ticks,
                                                DungeonTimers.bestTicks(state.floor, name),
                                            ),
                                    )
                                )
                            }
                            .orEmpty(),
                    Kind.TICKS to
                        if (!DungeonTimers.settings.dungeonOnly || DungeonTimers.inDungeon)
                            listOf(
                                Line(
                                    value.toString(),
                                    color = DungeonTimerState.countdownColor(value),
                                )
                            )
                        else emptyList(),
                )
        }
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("mithrilpf", "timers")) { g, _ ->
            val client = Minecraft.getInstance()
            if (
                client.options.hideGui ||
                    client.level == null ||
                    client.screen is TrackingHudEditor ||
                    !DungeonTimers.settings.enabled
            )
                return@addLast
            Kind.entries.filter(::enabled).forEach { kind ->
                render(g, kind, position(kind), false)
            }
        }
    }

    private fun lines(kind: Kind, preview: Boolean): List<Line> {
        if (!preview) return snapshots[kind].orEmpty()
        return when (kind) {
            Kind.TICKS -> listOf(Line("19", color = DungeonTimerState.countdownColor(19)))
            Kind.SPLIT -> listOf(Line("12.35s", color = DungeonPbColor.GREEN))
            Kind.TABLE ->
                listOf(
                    Line("M7"),
                    Line(
                        DungeonTimers.splitName("Blood Open"),
                        DungeonTimeFormat.styledPair(SplitTime(20450, 400), DungeonPbColor.GREEN),
                    ),
                    Line(
                        DungeonTimers.splitName("Watcher Clear"),
                        DungeonTimeFormat.styledPair(SplitTime(64200, 1280), DungeonPbColor.YELLOW),
                    ),
                    Line(
                        DungeonTimers.message("estimate").string,
                        Component.literal("4m 56.00s"),
                        footer = true,
                    ),
                    Line(
                        DungeonTimers.message("lag").string,
                        Component.literal("0.65s"),
                        DungeonPbColor.RED,
                        true,
                    ),
                )
        }
    }

    private fun dimensions(kind: Kind, rows: List<Line>): Pair<Int, Int> {
        val font = Minecraft.getInstance().font
        if (kind != Kind.TABLE) {
            val layout =
                NumericCounterLayout.measure(
                    rows.maxOfOrNull { font.width(it.label) } ?: 0,
                    font.width(if (kind == Kind.TICKS) "19" else "88.88s"),
                    font.lineHeight,
                )
            return layout.width to layout.height
        }
        val labels = rows.maxOfOrNull { font.width(it.label) } ?: 0
        val values = rows.maxOfOrNull { font.width(it.value) } ?: 0
        return (labels + 8 + values).coerceAtLeast(15) to ((rows.size * (font.lineHeight + 3)) + 4)
    }

    fun size(kind: Kind, position: HudPosition, preview: Boolean = true): Pair<Int, Int> {
        val (w, h) = dimensions(kind, lines(kind, preview))
        return (w * position.scale).toInt() to (h * position.scale).toInt()
    }

    fun origin(
        kind: Kind,
        position: HudPosition,
        width: Int,
        height: Int,
        preview: Boolean = true,
    ): Pair<Int, Int> {
        val (w, h) = size(kind, position, preview)
        return (position.x * (width - w).coerceAtLeast(0)).toInt() to
            (position.y * (height - h).coerceAtLeast(0)).toInt()
    }

    fun render(g: GuiGraphicsExtractor, kind: Kind, position: HudPosition, preview: Boolean) {
        val rows = lines(kind, preview)
        if (rows.isEmpty()) return
        val (w, h) = dimensions(kind, rows)
        val (x, y) = origin(kind, position, g.guiWidth(), g.guiHeight(), preview)
        val font = Minecraft.getInstance().font
        g.pose().pushMatrix()
        g.pose().translate(x.toFloat(), y.toFloat())
        g.pose().scale(position.scale.toFloat(), position.scale.toFloat())
        rows.forEachIndexed { index, row ->
            val top =
                if (kind == Kind.TABLE) index * (font.lineHeight + 3) + if (row.footer) 4 else 0
                else (h - font.lineHeight) / 2
            if (row.footer && rows.getOrNull(index - 1)?.footer != true)
                g.fill(0, top - 4, w, top - 3, Palette.BORDER)
            g.text(
                font,
                row.label,
                if (kind == Kind.TABLE) 0 else (w - font.width(row.label)) / 2,
                top,
                if (kind == Kind.TABLE) Palette.ACCENT else row.color,
                false,
            )
            if (kind == Kind.TABLE)
                g.text(
                    font,
                    row.value,
                    DungeonTimeColumn.textX(w, font.width(row.value)),
                    top,
                    row.color,
                    false,
                )
        }
        g.pose().popMatrix()
    }
}
