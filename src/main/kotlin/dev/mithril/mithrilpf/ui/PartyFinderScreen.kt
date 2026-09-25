package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.account.BrowserLink
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component

class PartyFinderScreen(private val parent: Screen?, private val browserLink: BrowserLink) :
    Screen(Component.translatable("screen.mithrilpf.title")) {
    private lateinit var panel: PanelLayout
    private lateinit var linkButton: FlatButton
    private lateinit var relinkButton: FlatButton

    override fun init() {
        panel = PanelLayout.fit(width, height)
        browserLink.show()
        val buttonWidth = (panel.width - 24).coerceAtLeast(1)
        linkButton =
            addRenderableWidget(
                FlatButton(
                    panel.x + 12,
                    panel.y + panel.height - 56,
                    buttonWidth,
                    Component.empty(),
                    primary = true,
                ) {
                    if (browserLink.linked) browserLink.openWebsite() else browserLink.start()
                }
            )
        relinkButton =
            addRenderableWidget(
                FlatButton(
                    panel.x + 12,
                    panel.y + panel.height - 82,
                    buttonWidth,
                    Component.translatable("screen.mithrilpf.relink"),
                ) {
                    browserLink.start()
                }
            )
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
        updateButton()
    }

    override fun tick() {
        browserLink.tick()
        updateButton()
    }

    private fun updateButton() {
        linkButton.active = !browserLink.working || browserLink.linked
        linkButton.message =
            Component.translatable(
                if (browserLink.linked) "screen.mithrilpf.open_website" else "screen.mithrilpf.link"
            )
        relinkButton.visible = browserLink.linked
        relinkButton.active = !browserLink.working
    }

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        g.fill(0, 0, width, height, Palette.BACKGROUND)
        g.fill(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height, Palette.BORDER)
        g.fill(
            panel.x + 1,
            panel.y + 1,
            panel.x + panel.width - 1,
            panel.y + panel.height - 1,
            Palette.SURFACE,
        )
        g.fill(panel.x + 1, panel.y + 27, panel.x + panel.width - 1, panel.y + 28, Palette.BORDER)
        line(g, title, 12, Palette.TEXT)
        line(g, Component.translatable("screen.mithrilpf.party_finder"), 34, Palette.TEXT)
        line(g, Component.translatable("screen.mithrilpf.development"), 52, Palette.MUTED)
        line(
            g,
            Component.translatable(
                "screen.mithrilpf.link.${browserLink.status}",
                minecraft.user.name,
            ),
            70,
            when (browserLink.status) {
                "failed",
                "storage_failed" -> Palette.DANGER
                "linked" -> Palette.SUCCESS
                else -> Palette.MUTED
            },
        )
        line(g, Component.translatable("screen.mithrilpf.website"), 94, Palette.ACCENT)
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    private fun line(g: GuiGraphicsExtractor, text: Component, offset: Int, color: Int) {
        if (offset + 10 > panel.height - (if (browserLink.linked) 90 else 64)) return
        g.text(
            font,
            Language.getInstance()
                .getVisualOrder(font.substrByWidth(text, (panel.width - 24).coerceAtLeast(0))),
            panel.x + 12,
            panel.y + offset,
            color,
            false,
        )
    }

    override fun onClose() {
        browserLink.cancel()
        minecraft.setScreen(parent)
    }

    override fun removed() {
        browserLink.cancel()
    }

    override fun isPauseScreen() = false
}
