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
        panel = UpdatePromptModel.panel(width, height)
        val status =
            UpdatePromptModel.statusKey(shown.state)?.let {
                Component.translatable(it, shown.version, shown.unmet.joinToString(", "))
            }
        statusLines = status?.let { font.split(it, UpdatePromptModel.columnWidth(panel)) }.orEmpty()
        val actions = UpdatePromptModel.actions(shown.state)
        val release = shown.release
        val statusHeight = if (statusLines.isEmpty()) 0 else statusLines.size * 10 + 6
        val layout =
            UpdatePromptModel.layout(
                panel,
                statusHeight,
                actions.size,
                if (release != null) 2 else 0,
            )
        notes = layout.notes
        column = layout.column
        actions.forEachIndexed { index, action ->
            addRenderableWidget(
                FlatButton(
                        column.x,
                        layout.firstButton + index * 24,
                        column.width,
                        Component.translatable(action.key),
                        action.primary,
                    ) {
                        run(action)
                    }
                    .also { it.danger = action.danger }
            )
        }
        if (release != null)
            listOf("github" to release.page, "modrinth" to MODRINTH).forEachIndexed {
                index,
                (key, uri) ->
                addRenderableWidget(
                    FlatButton(
                        column.x,
                        layout.firstLink + index * 24,
                        column.width,
                        message(key),
                    ) {
                        open(uri)
                    }
                )
            }
        lines = noteLines()
        scroll = scroll.coerceIn(0, maxScroll())
    }

    private fun run(action: UpdatePromptModel.Action) =
        when (action) {
            UpdatePromptModel.Action.INSTALL,
            UpdatePromptModel.Action.RETRY -> updates.install()
            UpdatePromptModel.Action.INSTALL_ALL -> updates.installWithDependencies()
            UpdatePromptModel.Action.CANCEL -> updates.cancel()
            UpdatePromptModel.Action.SKIP -> {
                updates.skip()
                minecraft.setScreen(parent)
            }
            UpdatePromptModel.Action.REMIND,
            UpdatePromptModel.Action.DONE -> onClose()
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
