package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.dungeontimer.DungeonTimerHud
import dev.mithril.mithrilpf.dungeontimer.DungeonTimers
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/** Editing uses synthetic display data only; no timer or record events are generated. */
class TrackingHudEditor(private val parent: Screen?) : Screen(DungeonTimers.message("edit")) {
    private var draft = DungeonTimers.settings
    private var dragged: DungeonTimerHud.Kind? = null
    private var grabX = 0.0
    private var grabY = 0.0

    override fun init() {
        addRenderableWidget(
            FlatButton(width / 2 - 50, height - 26, 100, Component.translatable("gui.done")) {
                onClose()
            }
        )
    }

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        g.fill(0, 0, width, height, 0x88000000.toInt())
        g.text(font, DungeonTimers.message("edit_hint"), 8, 8, Palette.TEXT, false)
        DungeonTimerHud.Kind.entries.forEach { kind ->
            val pos = DungeonTimerHud.position(kind, draft)
            val (x, y) = DungeonTimerHud.origin(kind, pos, width, height)
            val (w, h) = DungeonTimerHud.size(kind, pos)
            g.fill(x - 1, y - 1, x + w + 1, y, Palette.ACCENT)
            g.fill(x - 1, y + h, x + w + 1, y + h + 1, Palette.ACCENT)
            g.fill(x - 1, y, x, y + h, Palette.ACCENT)
            g.fill(x + w, y, x + w + 1, y + h, Palette.ACCENT)
            DungeonTimerHud.render(g, kind, pos, true)
        }
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    private fun hit(x: Double, y: Double) =
        DungeonTimerHud.Kind.entries.reversed().firstOrNull { kind ->
            val p = DungeonTimerHud.position(kind, draft)
            val (left, top) = DungeonTimerHud.origin(kind, p, width, height)
            val (w, h) = DungeonTimerHud.size(kind, p)
            x >= left && y >= top && x < left + w && y < top + h
        }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(event, doubled)) return true
        if (event.button() != 0) return false
        val kind = hit(event.x(), event.y()) ?: return false
        dragged = kind
        val (x, y) =
            DungeonTimerHud.origin(kind, DungeonTimerHud.position(kind, draft), width, height)
        grabX = event.x() - x
        grabY = event.y() - y
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val kind = dragged ?: return false
        val pos = DungeonTimerHud.position(kind, draft)
        val (w, h) = DungeonTimerHud.size(kind, pos)
        draft =
            DungeonTimerHud.move(
                draft,
                kind,
                pos.copy(
                    x = ((event.x() - grabX) / (width - w).coerceAtLeast(1)).coerceIn(0.0, 1.0),
                    y = ((event.y() - grabY) / (height - h).coerceAtLeast(1)).coerceIn(0.0, 1.0),
                ),
            )
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragged = null
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val kind = hit(mouseX, mouseY) ?: return false
        val pos = DungeonTimerHud.position(kind, draft)
        draft =
            DungeonTimerHud.move(
                draft,
                kind,
                pos.copy(scale = (pos.scale + scrollY * 0.05).coerceIn(0.5, 10.0)),
            )
        return true
    }

    override fun removed() {
        DungeonTimers.update(draft)
    }

    override fun onClose() {
        minecraft.setScreen(parent)
    }

    override fun isPauseScreen() = false
}
