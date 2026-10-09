package dev.mithril.mithrilpf.ui

import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import dev.mithril.mithrilpf.MithrilPF
import dev.mithril.mithrilpf.games.CatalogItem
import dev.mithril.mithrilpf.games.Column
import dev.mithril.mithrilpf.games.CuratorClient
import dev.mithril.mithrilpf.games.CuratorDay
import dev.mithril.mithrilpf.games.CuratorFormat
import dev.mithril.mithrilpf.games.CuratorGuess
import dev.mithril.mithrilpf.games.CuratorSearch
import dev.mithril.mithrilpf.games.ItemIcon
import dev.mithril.mithrilpf.games.Leaderboard
import dev.mithril.mithrilpf.games.LegacyMaterials
import dev.mithril.mithrilpf.games.Match
import dev.mithril.mithrilpf.games.RoundState
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import org.lwjgl.glfw.GLFW

/** The Games screen (/games): Curator's daily round and its season leaderboard. */
class GamesScreen(private val parent: Screen?, private val games: CuratorClient) :
    Screen(Component.translatable("games.mithrilpf.title")) {
    private lateinit var layout: GamesLayout
    private var input: EditBox? = null
    private var draft = ""
    private var suggestions = listOf<CatalogItem>()
    private var highlighted = 0
    private var scroll = 0
    private var shown = -1
    private var copiedUntil = 0L
    private var notice: String? = null
    private var drawn: Pair<String, ItemStack>? = null
    private var boardRequested = false

    override fun init() {
        layout = GamesLayout.fit(width, height)
        shown = games.revision
        val p = layout.panel
        addRenderableWidget(
            FinderTabButton(69, text("tab.curator"), selected = true) {}
                .also { it.setPosition(p.x + 104, p.y + 2) }
        )
        val right = layout.pageX + layout.pageWidth
        addRenderableWidget(
            FlatButton(right - 148, layout.top, 72, text("today"), view == View.TODAY) {
                switch(View.TODAY)
            }
        )
        addRenderableWidget(
            FlatButton(right - 72, layout.top, 72, text("leaderboard"), view == View.LEADERBOARD) {
                switch(View.LEADERBOARD)
            }
        )
        input = null
        if (!games.signedIn) {
            addRenderableWidget(
                FlatButton(layout.pageX, layout.top + 44, 160, text("open_finder"), true) {
                    minecraft.setScreen(MithrilPF.screen(this))
                }
            )
            return
        }
        if (view == View.LEADERBOARD) {
            // Once per opening or switch; a failed load waits for the next one.
            if (games.board == null && !boardRequested) {
                boardRequested = true
                games.loadBoard()
            }
            return
        }
        val day = games.today ?: return
        when {
            games.newDay ->
                addRenderableWidget(
                    FlatButton(layout.pageX, layout.inputY, 160, text("load_new"), true) {
                        games.reload()
                    }
                )
            day.state == RoundState.PLAYING -> playing()
            day.finished ->
                addRenderableWidget(
                    FlatButton(right - 110, layout.cardY + 14, 100, copyLabel(), true) { copy(day) }
                )
            else -> Unit
        }
    }

    private fun playing() {
        val box =
            EditBox(font, layout.pageX, layout.inputY, layout.pageWidth - 136, 20, text("guess"))
        box.setMaxLength(64)
        box.setHint(text("guess.hint"))
        box.setValue(draft)
        box.setEditable(!games.guessing)
        box.setResponder {
            draft = it
            notice = null
            refreshSuggestions()
        }
        input = addRenderableWidget(box)
        addRenderableWidget(
                FlatButton(
                    layout.pageX + layout.pageWidth - 130,
                    layout.inputY,
                    130,
                    if (games.guessing) Component.literal("…") else text("guess"),
                    true,
                ) {
                    submit(pickOnly = false)
                }
            )
            .active = !games.guessing
    }

    override fun setInitialFocus() {
        input?.let { setInitialFocus(it) } ?: super.setInitialFocus()
    }

    private fun switch(next: View) {
        view = next
        scroll = 0
        boardRequested = false
        rebuildWidgets()
    }

    private fun refreshSuggestions() {
        val guessed = games.today?.guesses.orEmpty().map { it.item }.toSet()
        suggestions =
            if (CuratorSearch.exact(games.catalog?.items.orEmpty(), draft) != null) emptyList()
            else CuratorSearch.suggestions(games.catalog?.items.orEmpty(), draft, guessed)
        highlighted = 0
    }

    /** Enter and Tab pick the highlighted suggestion; a whole item name is guessed. */
    private fun submit(pickOnly: Boolean) {
        val items = games.catalog?.items.orEmpty()
        val exact = CuratorSearch.exact(items, draft)
        val picked = suggestions.getOrNull(highlighted)
        when {
            exact != null && !pickOnly -> guess(exact)
            picked != null && pickOnly -> {
                draft = picked.name
                input?.setValue(picked.name)
                suggestions = emptyList()
            }
            picked != null -> guess(picked)
            draft.isNotBlank() -> notice = "pick_item"
        }
    }

    private fun guess(item: CatalogItem) {
        games.guess(item)
        draft = ""
        suggestions = emptyList()
        scroll = 0
    }

    private fun copy(day: CuratorDay) {
        minecraft.keyboardHandler.setClipboard(CuratorFormat.share(day))
        copiedUntil = System.currentTimeMillis() + 2000
        rebuildWidgets()
    }

    private fun copyLabel() =
        text(if (System.currentTimeMillis() < copiedUntil) "copied" else "copy")

    override fun tick() {
        val copied = copiedUntil != 0L && System.currentTimeMillis() >= copiedUntil
        if (copied) copiedUntil = 0
        if (shown != games.revision || copied) {
            val focused = input?.isFocused == true
            rebuildWidgets()
            if (focused) input?.let { setFocused(it) }
            refreshSuggestions()
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val box = input
        if (box != null && box.isFocused) {
            val open = suggestions.isNotEmpty()
            when (event.key()) {
                GLFW.GLFW_KEY_DOWN ->
                    if (open) {
                        highlighted = (highlighted + 1) % suggestions.size
                        return true
                    }
                GLFW.GLFW_KEY_UP ->
                    if (open) {
                        highlighted = (highlighted - 1 + suggestions.size) % suggestions.size
                        return true
                    }
                GLFW.GLFW_KEY_TAB ->
                    if (open) {
                        submit(pickOnly = true)
                        return true
                    }
                GLFW.GLFW_KEY_ENTER,
                GLFW.GLFW_KEY_KP_ENTER -> {
                    submit(pickOnly = open)
                    return true
                }
                GLFW.GLFW_KEY_ESCAPE ->
                    if (open) {
                        suggestions = emptyList()
                        return true
                    }
            }
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val row = suggestionAt(event.x().toInt(), event.y().toInt())
        if (row != null) {
            highlighted = row
            submit(pickOnly = true)
            input?.let { setFocused(it) }
            return true
        }
        return super.mouseClicked(event, doubled)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val day = games.today ?: return false
        val hidden = (day.guesses.size - layout.visibleRows(day.finished)).coerceAtLeast(0)
        scroll = (scroll + scrollY.toInt()).coerceIn(0, hidden)
        return true
    }

    private fun suggestionAt(x: Int, y: Int): Int? {
        val box = input ?: return null
        if (suggestions.isEmpty() || x !in box.x until box.x + dropdownWidth()) return null
        val top = box.y - 1 - suggestions.size * SUGGESTION
        return ((y - top) / SUGGESTION).takeIf { y >= top && it in suggestions.indices }
    }

    private fun dropdownWidth() = minOf(200, input?.width ?: 200)

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        g.fill(0, 0, width, height, 0xC0101114.toInt())
        val p = layout.panel
        g.fill(p.x, p.y, p.x + p.width, p.y + p.height, Palette.BORDER)
        g.fill(p.x + 1, p.y + 1, p.x + p.width - 1, p.y + p.height - 1, Palette.BACKGROUND)
        g.text(font, title, p.x + 8, p.y + 9, Palette.TEXT, false)
        val name = Component.literal(minecraft.user.name)
        g.text(font, name, p.x + p.width - 8 - font.width(name), p.y + 9, Palette.MUTED, false)
        g.fill(p.x + 1, p.y + 26, p.x + p.width - 1, p.y + 27, Palette.BORDER)
        g.fill(p.x + 1, p.y + p.height - 22, p.x + p.width - 1, p.y + p.height - 21, Palette.BORDER)
        g.text(
            font,
            font.plainSubstrByWidth(text(footer()).string, p.width - 16),
            p.x + 8,
            p.y + p.height - 15,
            Palette.MUTED,
            false,
        )
        var tooltip: List<Component>? = null
        when {
            !games.signedIn -> line(g, text("signed_out"), Palette.MUTED)
            view == View.LEADERBOARD -> leaderboard(g)
            else -> tooltip = today(g, mouseX, mouseY)
        }
        super.extractRenderState(g, mouseX, mouseY, delta)
        dropdown(g)
        tooltip?.let { lines ->
            g.setTooltipForNextFrame(lines.map { it.visualOrderText }, mouseX, mouseY)
        }
    }

    private fun footer() =
        when {
            view == View.LEADERBOARD -> "footer.season"
            games.today?.finished == true -> "footer.share"
            else -> "footer.keys"
        }

    private fun today(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): List<Component>? {
        val day = games.today
        if (day == null) {
            line(
                g,
                text(if (games.error != null) "error.unavailable" else "loading"),
                Palette.MUTED,
            )
            return null
        }
        if (day.state == RoundState.PREPARING) {
            line(g, text("preparing"), Palette.MUTED)
            status(g, text("next_in", countdown(day)), Palette.MUTED)
            return null
        }
        val number = text("number", day.number ?: 0)
        g.text(font, number, layout.pageX, layout.top + 6, Palette.TEXT, false)
        val count =
            text(if (layout.wide) "guesses" else "guesses.short", day.guesses.size, day.limit)
        val countX = layout.pageX + font.width(number) + 8
        g.text(font, count, countX, layout.top + 6, Palette.MUTED, false)
        headers(g)
        val tooltip = rows(g, day, mouseX, mouseY) ?: headerTooltip(mouseX, mouseY)
        when {
            day.finished -> card(g, day)
            else -> status(g, statusLine(day, mouseX, mouseY), statusColor())
        }
        return tooltip
    }

    private fun statusColor() =
        if (games.error != null || notice != null) Palette.DANGER else Palette.MUTED

    private fun statusLine(day: CuratorDay, mouseX: Int, mouseY: Int): Component {
        games.error?.let {
            return error(it)
        }
        notice?.let {
            return text(it)
        }
        if (games.newDay) return text("new_day")
        if (!layout.wide)
            hoveredRow(day, mouseX, mouseY)?.let { index ->
                val guess = day.guesses[index]
                val family = if (guess.family) text("family.short").string else ""
                return Component.literal("#${index + 1} ${guess.name}$family · ")
                    .append(text("left", day.limit - day.guesses.size))
            }
        return text("left", day.limit - day.guesses.size)
            .append(" · ")
            .append(text("next_in", countdown(day)))
    }

    private fun error(value: String) = text("error.$value")

    private fun headers(g: GuiGraphicsExtractor) {
        val first = if (layout.wide) text("column.item") else Component.literal("#")
        val firstX =
            if (layout.wide) layout.gridX + 4
            else layout.gridX + (layout.nameWidth - font.width(first)) / 2
        g.text(font, first, firstX, layout.headerY, Palette.MUTED, false)
        for (column in Column.entries) {
            val label = columnLabel(column)
            val x = layout.cellX(column.ordinal) + (layout.cell - font.width(label)) / 2
            g.text(font, label, x, layout.headerY, Palette.MUTED, false)
        }
    }

    private fun columnLabel(column: Column) =
        text("column.${column.key}." + if (layout.wide) "label" else "short")

    private fun headerTooltip(mouseX: Int, mouseY: Int): List<Component>? {
        if (mouseY !in layout.headerY - 2 until layout.headerY + 10) return null
        val column = Column.entries.firstOrNull { mouseX in cellRange(it) } ?: return null
        return listOf(text("column.${column.key}"), text("column.${column.key}.help"))
    }

    private fun cellRange(column: Column) =
        layout.cellX(column.ordinal) until layout.cellX(column.ordinal) + layout.cell

    private fun window(day: CuratorDay): IntRange {
        val visible = layout.visibleRows(day.finished)
        val end = (day.guesses.size - scroll).coerceAtLeast(0)
        return (end - visible).coerceAtLeast(0) until end
    }

    private fun hoveredRow(day: CuratorDay, mouseX: Int, mouseY: Int): Int? {
        if (mouseX !in layout.gridX until layout.gridX + layout.gridWidth) return null
        val range = window(day)
        val slot = (mouseY - layout.rowsY).floorDiv(GamesLayout.ROW)
        if (mouseY < layout.rowsY || (mouseY - layout.rowsY) % GamesLayout.ROW >= 16) return null
        return (range.first + slot).takeIf { it in range }
    }

    private fun rows(
        g: GuiGraphicsExtractor,
        day: CuratorDay,
        mouseX: Int,
        mouseY: Int,
    ): List<Component>? {
        val range = window(day)
        for ((slot, index) in range.withIndex()) row(
            g,
            day.guesses[index],
            index,
            layout.rowY(slot),
        )
        // Empty slots show how many guesses are left, when there is room for all of them.
        if (layout.wide && !day.finished) {
            val free = layout.visibleRows(false) - range.count()
            for (slot in
                range.count() until
                    range.count() + minOf(free, day.limit - day.guesses.size)) outline(
                g,
                layout.gridX,
                layout.rowY(slot),
                layout.gridWidth,
                16,
            )
        }
        val index = hoveredRow(day, mouseX, mouseY) ?: return null
        val guess = day.guesses[index]
        val column = Column.entries.firstOrNull { mouseX in cellRange(it) }
        if (column == null)
            return listOfNotNull(
                Component.literal(guess.name),
                if (guess.family) text("family.help") else null,
            )
        val feedback = guess.feedback.getValue(column)
        val hint =
            when {
                feedback.match == Match.EXACT -> text("hint.exact")
                feedback.match == Match.PARTIAL -> text("hint.partial.${column.key}")
                feedback.arrow != null ->
                    text("hint.${feedback.arrow.name.lowercase()}.${column.key}")
                else -> text("hint.none")
            }
        return listOf(
            text("column.${column.key}").append(": ${CuratorFormat.full(column, guess.values)}"),
            hint.withStyle { it.withColor(Palette.MUTED) },
        )
    }

    private fun row(g: GuiGraphicsExtractor, guess: CuratorGuess, index: Int, y: Int) {
        val x = layout.gridX
        g.fill(x, y, x + layout.nameWidth, y + 16, Palette.SURFACE)
        if (layout.wide) {
            val badge = if (guess.family) text("family") else null
            val room = layout.nameWidth - 8 - (badge?.let { font.width(it) + 4 } ?: 0)
            g.text(
                font,
                font.plainSubstrByWidth(guess.name, room),
                x + 4,
                y + 4,
                Palette.TEXT,
                false,
            )
            badge?.let {
                g.text(
                    font,
                    it,
                    x + layout.nameWidth - 4 - font.width(it),
                    y + 4,
                    Palette.ACCENT,
                    false,
                )
            }
        } else {
            val number = (index + 1).toString()
            g.text(
                font,
                number,
                x + (layout.nameWidth - font.width(number)) / 2,
                y + 4,
                Palette.MUTED,
                false,
            )
        }
        for (column in Column.entries) {
            val feedback = guess.feedback.getValue(column)
            val cellX = layout.cellX(column.ordinal)
            val fill =
                when (feedback.match) {
                    Match.EXACT -> Palette.MATCH
                    Match.PARTIAL -> Palette.PARTIAL
                    Match.NONE -> Palette.SURFACE_RAISED
                }
            g.fill(cellX, y, cellX + layout.cell, y + 16, fill)
            val value =
                CuratorFormat.cell(column, guess.values, !layout.wide) +
                    CuratorFormat.arrow(feedback, !layout.wide)
            val shown = font.plainSubstrByWidth(value, layout.cell - 2)
            g.text(
                font,
                shown,
                cellX + (layout.cell - font.width(shown)) / 2,
                y + 4,
                Palette.TEXT,
                false,
            )
        }
    }

    private fun outline(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int) {
        g.fill(x, y, x + w, y + 1, Palette.BORDER)
        g.fill(x, y + h - 1, x + w, y + h, Palette.BORDER)
        g.fill(x, y, x + 1, y + h, Palette.BORDER)
        g.fill(x + w - 1, y, x + w, y + h, Palette.BORDER)
    }

    private fun card(g: GuiGraphicsExtractor, day: CuratorDay) {
        val answer = day.answer ?: return
        val x = layout.pageX
        val y = layout.cardY
        val w = layout.pageWidth
        g.fill(x, y, x + w, y + GamesLayout.CARD, Palette.BORDER)
        g.fill(x + 1, y + 1, x + w - 1, y + GamesLayout.CARD - 1, Palette.SURFACE)
        g.fill(x + 8, y + 14, x + 28, y + 34, Palette.SURFACE_RAISED)
        g.item(stack(answer.item, answer.icon), x + 10, y + 16)
        val color = Palette.rarity(answer.values.rarity)
        val nameWidth = if (layout.wide) 160 else 110
        g.text(font, font.plainSubstrByWidth(answer.name, nameWidth), x + 34, y + 14, color, false)
        val kind =
            listOfNotNull(answer.values.rarity, answer.values.type).joinToString(" ") {
                it.replace('_', ' ')
            }
        g.text(font, font.plainSubstrByWidth(kind, nameWidth), x + 34, y + 25, color, false)
        val stats = day.stats
        val lines =
            listOfNotNull(
                if (day.state == RoundState.SOLVED)
                    text("solved", day.guesses.size, day.limit) to Palette.SUCCESS
                else text("not_solved", day.limit) to Palette.DANGER,
                stats?.let { text("streak", it.streak, it.bestStreak) to Palette.MUTED },
                stats?.let {
                    val rate = if (it.played == 0) 0 else it.solved * 100 / it.played
                    text("played", it.played, rate) to Palette.MUTED
                },
                (if (games.newDay) text("new_day") else text("next_in", countdown(day))) to
                    Palette.MUTED,
            )
        val textX = x + 34 + nameWidth + 12
        val room = x + w - 118 - textX
        lines.forEachIndexed { i, (line, lineColor) ->
            g.text(
                font,
                font.plainSubstrByWidth(line.string, room),
                textX,
                y + 4 + i * 11,
                lineColor,
                false,
            )
        }
    }

    private fun stack(item: String, icon: ItemIcon): ItemStack {
        drawn
            ?.takeIf { it.first == item }
            ?.let {
                return it.second
            }
        val id = LegacyMaterials.itemId(icon.material, icon.durability)
        val base =
            BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(id))
                .orElse(Items.PAPER)
        val stack = ItemStack(base)
        if (base == Items.PLAYER_HEAD && icon.skin != null) {
            val textures =
                PropertyMap(ImmutableMultimap.of("textures", Property("textures", icon.skin)))
            val profile = GameProfile(UUID.nameUUIDFromBytes(icon.skin.toByteArray()), "", textures)
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
        }
        drawn = item to stack
        return stack
    }

    private fun leaderboard(g: GuiGraphicsExtractor) {
        val board = games.board
        if (board == null) {
            line(
                g,
                text(if (games.error != null) "error.unavailable" else "loading"),
                Palette.MUTED,
            )
            return
        }
        val month = monthName(board.season)
        g.text(font, text("season", month), layout.pageX, layout.top + 6, Palette.TEXT, false)
        g.text(
            font,
            text("season.day", board.day, board.days),
            layout.pageX + font.width(text("season", month)) + 8,
            layout.top + 6,
            Palette.MUTED,
            false,
        )
        val tableWidth = if (layout.wide) layout.pageWidth - 206 else layout.pageWidth
        table(g, board, tableWidth)
        if (layout.wide) seasonCard(g, board)
        val next = monthName(nextSeason(board.season))
        status(g, text("season.ends", next), Palette.MUTED)
    }

    private fun table(g: GuiGraphicsExtractor, board: Leaderboard, tableWidth: Int) {
        val x = layout.pageX
        val y = layout.headerY
        val columns = listOf(tableWidth - 104, tableWidth - 58, tableWidth - 6)
        g.text(font, "#", x + 16 - font.width("#"), y, Palette.MUTED, false)
        g.text(font, text("player"), x + 26, y, Palette.MUTED, false)
        listOf("points", "solved.column", "streak.column").forEachIndexed { i, key ->
            val label = text(key)
            g.text(font, label, x + columns[i] - font.width(label), y, Palette.MUTED, false)
        }
        val rows = board.top + listOfNotNull(board.you)
        val visible = ((layout.bottom - 14 - layout.rowsY) / GamesLayout.ROW).coerceAtLeast(1)
        var slot = 0
        for (row in rows) {
            if (slot >= visible) break
            if (row === board.you) {
                g.text(font, "…", x + 26, layout.rowY(slot) + 4, Palette.MUTED, false)
                slot++
            }
            val rowY = layout.rowY(slot)
            val fill =
                if (row.you) Palette.SURFACE_RAISED
                else if (slot % 2 == 0) Palette.SURFACE else Palette.BACKGROUND
            g.fill(x, rowY, x + tableWidth, rowY + 16, fill)
            if (row.you) g.fill(x, rowY, x + 2, rowY + 16, Palette.ACCENT)
            val rank = row.rank.toString()
            g.text(font, rank, x + 16 - font.width(rank), rowY + 4, Palette.MUTED, false)
            g.text(font, row.name, x + 26, rowY + 4, Palette.TEXT, false)
            listOf(row.points.toString(), "${row.solved}/${row.played}", row.streak.toString())
                .forEachIndexed { i, value ->
                    g.text(
                        font,
                        value,
                        x + columns[i] - font.width(value),
                        rowY + 4,
                        if (i == 0 || row.you) Palette.TEXT else Palette.MUTED,
                        false,
                    )
                }
            slot++
        }
        if (board.top.isEmpty())
            g.text(font, text("no_players"), x + 26, layout.rowY(0) + 4, Palette.MUTED, false)
    }

    private fun seasonCard(g: GuiGraphicsExtractor, board: Leaderboard) {
        val x = layout.pageX + layout.pageWidth - 194
        val y = layout.headerY - 4
        val bottom = layout.bottom - 14
        g.fill(x, y, x + 194, bottom, Palette.BORDER)
        g.fill(x + 1, y + 1, x + 193, bottom - 1, Palette.SURFACE)
        val stats = board.stats
        val lines =
            listOfNotNull(
                text("season.yours") to Palette.TEXT,
                (stats.rank?.let { text("rank", it, board.players) } ?: text("unranked")) to
                    Palette.TEXT,
                text("season.points", stats.points, stats.solved, stats.played) to Palette.MUTED,
                text("streak", stats.streak, stats.bestStreak) to Palette.MUTED,
                stats.average?.let {
                    text("average", String.format(Locale.ROOT, "%.1f", it)) to Palette.MUTED
                },
            )
        lines.forEachIndexed { i, (line, color) ->
            g.text(font, line, x + 8, y + 8 + i * 12, color, false)
        }
        val histogramTop = y + 8 + lines.size * 12 + 8
        g.text(font, text("histogram"), x + 8, histogramTop, Palette.TEXT, false)
        val base = bottom - 26
        val most = (stats.histogram.maxOrNull() ?: 0).coerceAtLeast(1)
        val height = (base - histogramTop - 14).coerceAtLeast(4)
        stats.histogram.forEachIndexed { i, count ->
            val barX = x + 10 + i * 18
            val barHeight = count * height / most
            if (barHeight > 0) g.fill(barX, base - barHeight, barX + 14, base, Palette.MATCH)
            val label = (i + 1).toString()
            g.text(font, label, barX + (14 - font.width(label)) / 2, base + 3, Palette.MUTED, false)
        }
        g.fill(x + 8, base, x + 186, base + 1, Palette.BORDER)
        g.text(font, text("failed", stats.failed), x + 8, bottom - 11, Palette.MUTED, false)
    }

    private fun monthName(season: String) =
        Month.of(season.substring(5, 7).toInt()).getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    private fun nextSeason(season: String): String {
        val month = season.substring(5, 7).toInt()
        return if (month == 12) "${season.take(4).toInt() + 1}-01"
        else "${season.take(4)}-%02d".format(month + 1)
    }

    private fun dropdown(g: GuiGraphicsExtractor) {
        val box = input ?: return
        if (suggestions.isEmpty() || !box.isFocused) return
        val w = dropdownWidth()
        val top = box.y - 1 - suggestions.size * SUGGESTION
        g.fill(box.x, top - 1, box.x + w, box.y - 1, Palette.BORDER)
        suggestions.forEachIndexed { i, item ->
            val y = top + i * SUGGESTION
            val selected = i == highlighted
            g.fill(
                box.x + 1,
                y,
                box.x + w - 1,
                y + SUGGESTION,
                if (selected) Palette.SECONDARY_HOVER else Palette.SURFACE_RAISED,
            )
            if (selected) g.fill(box.x + 1, y, box.x + 3, y + SUGGESTION, Palette.ACCENT)
            g.text(
                font,
                font.plainSubstrByWidth(item.name, w - 12),
                box.x + 6,
                y + 3,
                if (selected) Palette.TEXT else Palette.MUTED,
                false,
            )
        }
    }

    private fun line(g: GuiGraphicsExtractor, message: Component, color: Int) {
        g.text(font, message, layout.pageX, layout.top + 30, color, false)
    }

    private fun status(g: GuiGraphicsExtractor, message: Component, color: Int) {
        g.text(
            font,
            font.plainSubstrByWidth(message.string, layout.pageWidth),
            layout.pageX,
            layout.statusY,
            color,
            false,
        )
    }

    private fun countdown(day: CuratorDay): String {
        val seconds = (day.resetsAt - System.currentTimeMillis() / 1000).coerceAtLeast(0)
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }

    private fun text(key: String, vararg args: Any) =
        Component.translatable("games.mithrilpf.$key", *args)

    override fun onClose() {
        minecraft.setScreen(parent)
    }

    override fun isPauseScreen() = false

    enum class View {
        TODAY,
        LEADERBOARD,
    }

    companion object {
        private const val SUGGESTION = 12

        /** The chosen view survives reopening the screen. */
        var view = View.TODAY
    }
}
