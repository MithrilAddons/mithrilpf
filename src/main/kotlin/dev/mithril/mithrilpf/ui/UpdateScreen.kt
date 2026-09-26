package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.update.ModUpdates
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class UpdateScreen(private val parent: Screen, private val updates: ModUpdates) :
    Screen(Component.translatable("update.mithrilpf.title")) {
    private lateinit var panel: PanelLayout
    private var shown = updates.status

    override fun init() {
        panel = PanelLayout.fit(width, height)
        shown = updates.status
        val buttonWidth = (panel.width - 24).coerceAtLeast(1)
        fun toggle(key: String, value: Boolean) =
            Component.translatable(
                "tracking.mithrilpf.toggle",
                message(key),
                Component.translatable(if (value) "options.on" else "options.off"),
            )
        addRenderableWidget(
                FlatButton(
                    panel.x + 12,
                    panel.y + 36,
                    buttonWidth,
                    toggle("enabled", shown.settings.enabled),
                ) {
                    updates.configure(shown.settings.copy(enabled = !shown.settings.enabled))
                }
            )
            .active = shown.loaded
        addRenderableWidget(
                FlatButton(
                    panel.x + 12,
                    panel.y + 64,
                    buttonWidth,
                    toggle("prereleases", shown.settings.prereleases),
                ) {
                    updates.configure(
                        shown.settings.copy(prereleases = !shown.settings.prereleases)
                    )
                }
            )
            .active = shown.loaded
        addRenderableWidget(
                FlatButton(
                    panel.x + 12,
                    panel.y + panel.height - 58,
                    buttonWidth,
                    message("check"),
                ) {
                    updates.checkNow()
                }
            )
            .active =
            shown.loaded &&
                shown.settings.enabled &&
                shown.state !in setOf("checking", "downloading", "ready", "local")
        addRenderableWidget(
            FlatButton(
                panel.x + 12,
                panel.y + panel.height - 30,
                buttonWidth,
                Component.translatable("gui.done"),
            ) {
                onClose()
            }
        )
    }

    override fun tick() {
        if (shown != updates.status) rebuildWidgets()
    }

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        g.fill(0, 0, width, height, Palette.BACKGROUND)
        g.fill(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height, Palette.SURFACE)
        g.text(font, title, panel.x + 12, panel.y + 12, Palette.TEXT, false)
        val lines =
            font.split(message(shown.state, shown.version), (panel.width - 24).coerceAtLeast(1))
        lines.take(3).forEachIndexed { index, line ->
            g.text(font, line, panel.x + 12, panel.y + 98 + index * 10, Palette.MUTED, false)
        }
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    private fun message(key: String, vararg args: Any) =
        Component.translatable("update.mithrilpf.$key", *args)

    override fun onClose() {
        minecraft.setScreen(parent)
    }

    override fun isPauseScreen() = false
}
