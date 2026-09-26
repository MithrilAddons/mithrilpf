package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.account.BrowserLink
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class LinkScreen(private val parent: Screen, private val link: BrowserLink) :
    Screen(Component.translatable("screen.mithrilpf.link")) {
    private var started = false
    private var expanded = false
    private var copied = false
    private lateinit var primary: FlatButton
    private lateinit var alternatives: FlatButton
    private lateinit var panel: PanelLayout

    private fun options() =
        LinkOptions.from(expanded, link.issued != null, link.status, link.issued?.code)

    override fun init() {
        link.show()
        val panelWidth = (width - 16).coerceIn(1, 320)
        val panelHeight = (height - 16).coerceIn(1, if (expanded) 360 else 200)
        panel =
            PanelLayout(
                (width - panelWidth) / 2,
                (height - panelHeight) / 2,
                panelWidth,
                panelHeight,
            )
        val left = panel.x + 12
        val buttonWidth = (panel.width - 24).coerceAtLeast(1)
        val bottom = panel.y + panel.height
        primary =
            addRenderableWidget(
                FlatButton(left, bottom - 82, buttonWidth, Component.empty(), primary = true) {
                    when (options().action) {
                        LinkAction.OPEN -> link.openLink()
                        LinkAction.COPY -> {
                            link.copyLink()
                            copied = true
                        }
                        LinkAction.REFRESH -> {
                            copied = false
                            link.start()
                        }
                        LinkAction.WEBSITE -> link.openWebsite()
                    }
                }
            )
        alternatives =
            addRenderableWidget(
                FlatButton(left, bottom - 56, buttonWidth, Component.empty()) {
                    expanded = !expanded
                    rebuildWidgets()
                    setFocused(alternatives)
                }
            )
        addRenderableWidget(
            FlatButton(left, bottom - 30, buttonWidth, Component.translatable("gui.done")) {
                onClose()
            }
        )
        updateButtons()
    }

    override fun tick() {
        link.tick()
        if (!started && !link.working) {
            started = true
            if (link.status != "storage_failed") link.start()
        }
        updateButtons()
    }

    private fun updateButtons() {
        val state = options()
        primary.active =
            state.action != LinkAction.REFRESH || !link.working && link.status != "storage_failed"
        primary.message =
            Component.translatable(
                when (state.action) {
                    LinkAction.OPEN -> "screen.mithrilpf.link.open_browser"
                    LinkAction.COPY ->
                        if (copied) "screen.mithrilpf.link.copied" else "screen.mithrilpf.link.copy"
                    LinkAction.WEBSITE -> "screen.mithrilpf.open_website"
                    LinkAction.REFRESH ->
                        if (link.working) "screen.mithrilpf.link.busy"
                        else "screen.mithrilpf.link.refresh"
                }
            )
        alternatives.message =
            Component.translatable(
                if (expanded) "gui.back" else "screen.mithrilpf.link.other_device"
            )
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
        fun centered(text: Component, y: Int, color: Int = Palette.TEXT) {
            val clipped = font.plainSubstrByWidth(text.string, (panel.width - 24).coerceAtLeast(0))
            g.text(font, clipped, (width - font.width(clipped)) / 2, y, color, false)
        }
        val state = options()
        val bottom = panel.y + panel.height
        centered(title, panel.y + 12)
        if (state.showAlternatives) {
            val qr = link.qr
            val availableHeight = panel.height - if (state.code != null) 182 else 154
            val scale =
                if (qr == null) 0
                else minOf(availableHeight / qr.size, (panel.width - 24) / qr.size).coerceIn(0, 4)
            centered(
                Component.translatable(
                    if (scale > 0) "screen.mithrilpf.link.scan" else "screen.mithrilpf.link.paste"
                ),
                panel.y + 32,
                Palette.MUTED,
            )
            if (qr != null && scale > 0) {
                val size = qr.size * scale
                val x = (width - size) / 2
                val y = panel.y + 48
                g.fill(x, y, x + size, y + size, 0xFFFFFFFF.toInt())
                for (run in qr.runs) g.fill(
                    x + run.x * scale,
                    y + run.y * scale,
                    x + (run.x + run.width) * scale,
                    y + (run.y + 1) * scale,
                    0xFF000000.toInt(),
                )
            }
            state.code?.let {
                centered(
                    Component.translatable("screen.mithrilpf.link.manual_code", it),
                    bottom - 126,
                )
                centered(Component.literal("mithril.foo/link"), bottom - 112, Palette.MUTED)
            }
        } else {
            centered(Component.literal(minecraft.user.name), panel.y + 40)
            centered(
                Component.translatable("screen.mithrilpf.link.browser_hint"),
                panel.y + 60,
                Palette.MUTED,
            )
        }
        centered(
            if (link.issued != null)
                Component.translatable("screen.mithrilpf.link.remaining", link.secondsLeft)
            else
                Component.translatable("screen.mithrilpf.link.${link.status}", minecraft.user.name),
            bottom - 98,
            Palette.MUTED,
        )
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    override fun onClose() {
        minecraft.setScreen(parent)
    }

    override fun removed() {
        link.cancel()
    }

    override fun isPauseScreen() = false
}
