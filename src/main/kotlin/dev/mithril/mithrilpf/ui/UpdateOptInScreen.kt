package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.update.ModUpdates
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Shown once to new installs: nothing is checked until the player chooses. */
class UpdateOptInScreen(private val parent: Screen, private val updates: ModUpdates) :
    Screen(Component.translatable("update.mithrilpf.optin_title")) {
    private var panel = PanelLayout(0, 0, 1, 1)
    private var prereleases = false

    override fun init() {
        val width = (this.width - 16).coerceIn(1, 300)
        val height = (this.height - 16).coerceIn(1, 140)
        panel = PanelLayout((this.width - width) / 2, (this.height - height) / 2, width, height)
        val inner = panel.width - 24
        addRenderableWidget(
            FlatButton(
                panel.x + 12,
                panel.y + panel.height - 56,
                inner,
                Component.translatable(
                    "tracking.mithrilpf.toggle",
                    message("prereleases"),
                    Component.translatable(if (prereleases) "options.on" else "options.off"),
                ),
            ) {
                prereleases = !prereleases
                rebuildWidgets()
            }
        )
        val half = (inner - 6) / 2
        addRenderableWidget(
            FlatButton(
                panel.x + 12,
                panel.y + panel.height - 30,
                half,
                message("optin_accept"),
                true,
            ) {
                choose(true)
            }
        )
        addRenderableWidget(
            FlatButton(
                panel.x + 18 + half,
                panel.y + panel.height - 30,
                inner - 6 - half,
                message("optin_decline"),
            ) {
                choose(false)
            }
        )
    }

    private fun choose(enabled: Boolean) {
        updates.configure(
            updates.status.settings.copy(enabled = enabled, prereleases = prereleases, asked = true)
        )
        minecraft.setScreen(parent)
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
        font.split(message("optin_body"), panel.width - 24).take(4).forEachIndexed { index, line ->
            g.text(font, line, panel.x + 12, panel.y + 28 + index * 10, Palette.MUTED, false)
        }
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    private fun message(key: String) = Component.translatable("update.mithrilpf.$key")

    /** Escape leaves the choice open; the prompt returns on the next launch. */
    override fun onClose() {
        minecraft.setScreen(parent)
    }

    override fun isPauseScreen() = false
}
