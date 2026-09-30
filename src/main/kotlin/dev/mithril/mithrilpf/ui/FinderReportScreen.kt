package dev.mithril.mithrilpf.ui

import com.google.gson.JsonObject
import dev.mithril.mithrilpf.finder.FinderClient
import dev.mithril.mithrilpf.finder.FinderMessage
import dev.mithril.mithrilpf.finder.FinderParty
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen

class FinderReportScreen(
    private val parent: Screen,
    private val finder: FinderClient,
    private val party: FinderParty,
) : Screen(finderText("report.title")) {
    private var selected: FinderMessage? = null
    private var reason = ""
    private var failed = false

    override fun init() {
        val layout = FinderLayout.fit(width.coerceAtMost(600), height)
        val w = layout.pageWidth
        val page = LinearLayout.vertical().spacing(8)
        page.label(title, w)
        val message = selected
        if (message == null) {
            page.label(finderText("report.choose"), w, Palette.MUTED)
            val messages = party.messages.filter { it.name != null }
            if (messages.isEmpty()) page.label(finderText("party.no_messages"), w, Palette.MUTED)
            for (entry in messages) {
                page.label(
                    net.minecraft.network.chat.Component.literal("${entry.name}: ${entry.text}"),
                    w,
                )
                page.button(finderText("report.select", entry.name!!), w) {
                    selected = entry
                    rebuildWidgets()
                }
            }
        } else {
            page.label(
                net.minecraft.network.chat.Component.literal("${message.name}: ${message.text}"),
                w,
            )
            val input = page.input(finderText("report.reason"), w, reason, 500) { reason = it }
            val submit =
                page.button(
                    finderText("report.submit"),
                    w,
                    primary = true,
                    enabled = reason.trim().length >= 3 && !finder.busy,
                ) {
                    finder.action(
                        "chat/report",
                        JsonObject().apply {
                            addProperty("party_id", party.id)
                            addProperty("message_id", message.id)
                            addProperty("reason", reason.trim())
                        },
                    ) { success ->
                        if (success) minecraft.setScreen(parent)
                        else {
                            failed = true
                            rebuildWidgets()
                        }
                    }
                }
            input.setResponder {
                reason = it
                submit.active = it.trim().length >= 3 && !finder.busy
            }
            if (failed)
                page.label(finderText("error.${finder.error ?: "unavailable"}"), w, Palette.DANGER)
        }
        page.button(finderText("editor.back"), w) { onClose() }
        val scroll = ScrollableLayout(minecraft, page, height - 48)
        scroll.setMinWidth(w)
        scroll.arrangeElements()
        scroll.setPosition((width - w - 20) / 2, 24)
        scroll.visitWidgets { addRenderableWidget(it) }
    }

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        g.fill(0, 0, width, height, Palette.BACKGROUND)
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    override fun onClose() {
        minecraft.setScreen(parent)
    }

    override fun isPauseScreen() = false
}
