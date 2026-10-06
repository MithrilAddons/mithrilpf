package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.update.ModUpdates
import dev.mithril.mithrilpf.update.ReleaseNotes
import java.net.URI
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence

/** Asks before installing an update. Nothing is downloaded until "Install on exit". */
class UpdatePromptScreen(private val parent: Screen, private val updates: ModUpdates) :
    Screen(Component.translatable("update.mithrilpf.prompt_title")) {
    private var panel = PanelLayout(0, 0, 1, 1)
    private var notes = PanelLayout(0, 0, 1, 1)
    private var column = PanelLayout(0, 0, 1, 1)
    private var lines = listOf<Row>()
    private var statusLines = listOf<FormattedCharSequence>()
    private var scroll = 0
    private var shown = updates.status

    override fun init() {
        shown = updates.status
        val width = (this.width - 16).coerceIn(1, 420)
        val height = (this.height - 16).coerceIn(1, 260)
        panel = PanelLayout((this.width - width) / 2, (this.height - height) / 2, width, height)
        val top = panel.y + 38
        val bottom = panel.y + panel.height - 12
        val wide = panel.width >= 320
        val columnWidth = if (wide) 128 else panel.width - 24
        val status = statusMessage()
        statusLines = status?.let { font.split(it, columnWidth) }.orEmpty()
        val actions = actions()
        val links = if (shown.release != null) 2 else 0
        val statusHeight = if (statusLines.isEmpty()) 0 else statusLines.size * 10 + 6
        val buttonsHeight = (actions.size + links) * 24 + (if (links > 0) 8 else 0)
        column =
            if (wide)
                PanelLayout(
                    panel.x + panel.width - 12 - columnWidth,
                    top,
                    columnWidth,
                    bottom - top,
                )
            else {
                val height = (statusHeight + buttonsHeight).coerceAtMost(bottom - top - 40)
                PanelLayout(panel.x + 12, bottom - height, columnWidth, height)
            }
        notes =
            if (wide) PanelLayout(panel.x + 12, top, column.x - 12 - panel.x - 12, bottom - top)
            else PanelLayout(panel.x + 12, top, panel.width - 24, column.y - 8 - top)
        var y = column.y + statusHeight
        for ((label, primary, danger, action) in actions) {
            addRenderableWidget(
                FlatButton(column.x, y, column.width, label, primary, action).also {
                    it.danger = danger
                }
            )
            y += 24
        }
        shown.release?.let { release ->
            var link = if (wide) column.y + column.height - 48 else y + 8
            for ((key, uri) in listOf("github" to release.page, "modrinth" to MODRINTH)) {
                addRenderableWidget(
                    FlatButton(column.x, link, column.width, message(key)) { open(uri) }
                )
                link += 24
            }
        }
        lines = noteLines()
        scroll = scroll.coerceIn(0, maxScroll())
    }

    private data class Action(
        val label: Component,
        val primary: Boolean,
        val danger: Boolean,
        val run: () -> Unit,
    )

    private fun actions(): List<Action> =
        when (shown.state) {
            "available",
            "skipped" ->
                listOfNotNull(
                    Action(message("install"), true, false) { updates.install() },
                    Action(message("remind"), false, false) { onClose() },
                    if (shown.state == "available")
                        Action(message("skip"), false, true) {
                            updates.skip()
                            minecraft.setScreen(parent)
                        }
                    else null,
                )
            "downloading",
            "ready" ->
                listOf(
                    Action(message("cancel_install"), false, false) { updates.cancel() },
                    Action(Component.translatable("gui.done"), false, false) { onClose() },
                )
            "dependencies" ->
                listOf(
                    Action(message("install_all"), true, false) {
                        updates.installWithDependencies()
                    },
                    Action(Component.translatable("gui.done"), false, false) { onClose() },
                )
            "failed" ->
                listOf(
                    Action(message("retry"), true, false) { updates.install() },
                    Action(Component.translatable("gui.done"), false, false) { onClose() },
                )
            else -> listOf(Action(Component.translatable("gui.done"), false, false) { onClose() })
        }

    private fun statusMessage(): Component? =
        when (shown.state) {
            "available",
            "skipped" -> null
            else -> message(shown.state, shown.version, shown.unmet.joinToString(", "))
        }

    /** One wrapped notes line; [bullet] marks the first line of a bullet point. */
    private data class Row(
        val text: FormattedCharSequence,
        val color: Int,
        val indent: Int = 0,
        val bullet: Boolean = false,
    )

    private fun noteLines(): List<Row> {
        val notes = ReleaseNotes.parse(shown.release?.notes.orEmpty())
        if (notes.isEmpty())
            return font.split(message("no_notes"), this.notes.width).map { Row(it, Palette.MUTED) }
        val rows = mutableListOf<Row>()
        for ((index, note) in notes.withIndex()) {
            if (note.heading && index > 0) rows += Row(FormattedCharSequence.EMPTY, Palette.MUTED)
            val color = if (note.heading) Palette.ACCENT else Palette.TEXT
            val indent = if (note.bullet) font.width(BULLET) else 0
            font
                .split(Component.literal(note.text), (this.notes.width - indent).coerceAtLeast(1))
                .forEachIndexed { line, text ->
                    rows += Row(text, color, indent, note.bullet && line == 0)
                }
        }
        return rows
    }

    private fun maxScroll() = (lines.size * 10 - notes.height).coerceAtLeast(0)

    private fun open(uri: URI) = ConfirmLinkScreen.confirmLinkNow(this, uri)

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        scroll = (scroll - (scrollY * 10).toInt()).coerceIn(0, maxScroll())
        return true
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
        g.text(font, title, panel.x + 12, panel.y + 10, Palette.TEXT, false)
        val release = shown.release
        if (release != null) {
            var right = panel.x + panel.width - 12
            if (release.version.prerelease) {
                val tag = message("prerelease_label")
                right -= font.width(tag)
                g.text(font, tag, right, panel.y + 10, Palette.MUTED, false)
                right -= 6
            }
            g.text(
                font,
                release.version.text,
                right - font.width(release.version.text),
                panel.y + 10,
                Palette.SUCCESS,
                false,
            )
        }
        g.text(
            font,
            message("installed", updates.installedVersion),
            panel.x + 12,
            panel.y + 22,
            Palette.MUTED,
            false,
        )
        g.fill(panel.x, panel.y + 33, panel.x + panel.width, panel.y + 34, Palette.BORDER)
        g.enableScissor(notes.x, notes.y, notes.x + notes.width, notes.y + notes.height)
        lines.forEachIndexed { index, row ->
            val y = notes.y + index * 10 - scroll
            if (y > notes.y - 10 && y < notes.y + notes.height) {
                if (row.bullet) g.text(font, BULLET, notes.x, y, Palette.MUTED, false)
                g.text(font, row.text, notes.x + row.indent, y, row.color, false)
            }
        }
        g.disableScissor()
        if (maxScroll() > 0) {
            val track = notes.height
            val thumb = (track * notes.height / (lines.size * 10)).coerceAtLeast(8)
            val offset = (track - thumb) * scroll / maxScroll()
            g.fill(
                notes.x + notes.width + 3,
                notes.y + offset,
                notes.x + notes.width + 5,
                notes.y + offset + thumb,
                Palette.BORDER,
            )
        }
        statusLines.forEachIndexed { index, line ->
            val color = if (shown.state == "ready") Palette.SUCCESS else Palette.MUTED
            g.text(font, line, column.x, column.y + index * 10, color, false)
        }
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    private fun message(key: String, vararg args: Any) =
        Component.translatable("update.mithrilpf.$key", *args)

    /** Escape and "Remind me later" both wait until the next launch to ask again. */
    override fun onClose() {
        updates.remindLater()
        minecraft.setScreen(parent)
    }

    override fun isPauseScreen() = false

    private companion object {
        val MODRINTH: URI = URI("https://modrinth.com/mod/mithrilpf")
        const val BULLET = "• "
    }
}
