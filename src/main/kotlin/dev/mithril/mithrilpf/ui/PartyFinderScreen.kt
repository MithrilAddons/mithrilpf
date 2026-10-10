package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.MithrilPF
import dev.mithril.mithrilpf.account.BrowserLink
import dev.mithril.mithrilpf.dungeontimer.DungeonBest
import dev.mithril.mithrilpf.dungeontimer.DungeonTimeFormat
import dev.mithril.mithrilpf.dungeontimer.DungeonTimers
import dev.mithril.mithrilpf.dungeontimer.TrackingSettings
import dev.mithril.mithrilpf.finder.DungeonRole
import dev.mithril.mithrilpf.finder.FinderMetric
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ComponentPath
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractScrollArea
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import org.lwjgl.glfw.GLFW

class PartyFinderScreen(
    private val parent: Screen?,
    private val browserLink: BrowserLink,
    private val navigation: FinderNavigation,
) : Screen(Component.translatable("screen.mithrilpf.title")) {
    private lateinit var layout: FinderLayout
    private lateinit var scroll: ScrollableLayout
    private val bindings = mutableListOf<() -> Boolean>()
    private val tabButtons = mutableMapOf<FinderTab, FinderTabButton>()
    private var account = ""
    private var refreshTicks = 0
    private var editor: FinderDraft? = null
    private var editing = false
    private var viewStamp: List<Any?> = emptyList()
    private var displayedMembership: Pair<String?, Boolean?> = null to null
    private var displayedToken: String? = null
    private var floorButton: FlatButton? = null
    private var floorListOpen = false
    private var floorsWithPbs = emptySet<String>()
    private val version =
        FabricLoader.getInstance()
            .getModContainer("mithrilpf")
            .orElseThrow()
            .metadata
            .version
            .friendlyString

    override fun init() {
        val currentAccount = minecraft.user.profileId.toString()
        if (account.isNotEmpty() && account != currentAccount) {
            editor = null
            editing = false
        }
        account = currentAccount
        navigation.forAccount(account)
        layout = FinderLayout.fit(width, height)
        browserLink.show()
        bindings.clear()
        tabButtons.clear()
        floorButton = null
        if (navigation.tab != FinderTab.RECORDS) floorListOpen = false
        val tabs = LinearLayout.horizontal().spacing(4)
        val tabWidth = if (layout.wide) 69 else (layout.contentWidth - 12) / 4
        for (tab in FinderTab.entries) {
            tabButtons[tab] =
                tabs.addChild(
                    FinderTabButton(
                        tabWidth,
                        text("tab.${tab.key}"),
                        selected = navigation.tab == tab,
                    ) {
                        navigation.tab = tab
                        editor = null
                        rebuildWidgets()
                    }
                )
        }
        tabs.arrangeElements()
        tabs.setPosition(
            layout.contentX + if (layout.wide) 96 else 0,
            layout.panel.y + if (layout.wide) 2 else 22,
        )
        tabs.visitWidgets { addRenderableWidget(it) }
        val page =
            when (navigation.tab) {
                FinderTab.PARTIES ->
                    editor?.let { draft ->
                        FinderEditor(
                                MithrilPF.finder,
                                layout,
                                draft,
                                editing,
                                ::rebuildPreservingScroll,
                                {
                                    editor = null
                                    rebuildWidgets()
                                },
                                {
                                    editor = null
                                    navigation.tab = FinderTab.PARTY
                                    if (minecraft.screen === this) rebuildWidgets()
                                },
                                { floor ->
                                    finderDraft(floor)
                                    rebuildWidgets()
                                },
                            )
                            .build()
                    } ?: parties()
                FinderTab.PARTY -> party()
                FinderTab.RECORDS -> records()
                FinderTab.SETTINGS -> settings()
            }
        scroll = ScrollableLayout(minecraft, page, layout.contentHeight)
        scroll.setMinWidth(layout.pageWidth)
        scroll.setMinHeight(layout.contentHeight)
        scroll.arrangeElements()
        scroll.setPosition(layout.contentX, layout.contentY)
        scroll.visitWidgets { addRenderableWidget(it) }
        viewStamp = currentStamp()
        displayedMembership = MithrilPF.finder.state?.party.let { it?.id to it?.youLead }
        displayedToken = MithrilPF.nativeAccount.session?.token
    }

    override fun setInitialFocus() {
        tabButtons[navigation.tab]?.let { setInitialFocus(it) }
    }

    private fun parties(): LinearLayout {
        if (MithrilPF.nativeAccount.session == null)
            return LinearLayout.vertical().spacing(12).apply {
                paragraph(this, text("browse.welcome"), muted = false)
                accountActions(this)
            }
        return FinderBrowse(
                MithrilPF.finder,
                layout,
                navigation,
                ::rebuildPreservingScroll,
                {
                    editing = false
                    finderDraft(MithrilPF.finder.floor)
                    rebuildWidgets()
                },
                {
                    navigation.tab = FinderTab.PARTY
                    if (minecraft.screen === this) rebuildWidgets()
                },
            )
            .build()
    }

    private fun finderDraft(floor: String) {
        MithrilPF.finder.changeFloor(floor)
        navigation.floor = floor
        editor =
            navigation.drafts.getOrPut(floor) {
                FinderDraft(floor).apply { MithrilPF.finder.presets[floor]?.let(::restore) }
            }
    }

    private fun currentStamp(): List<Any?> {
        val finder = MithrilPF.finder
        return listOf(
            finder.listings,
            finder.detail,
            finder.state?.party?.copy(messages = emptyList()),
            finder.state?.stats,
            finder.state?.looking,
            finder.busy,
            finder.error,
            finder.offline,
            finder.presetsLoaded,
            finder.presetError,
            MithrilPF.nativeAccount.session,
            MithrilPF.nativeAccount.status,
            if (navigation.tab == FinderTab.RECORDS) DungeonTimers.records() else null,
            if (navigation.tab == FinderTab.RECORDS) MithrilPF.syncStatus else null,
        )
    }

    private fun focusedLeaf(): GuiEventListener? {
        var current = focused
        while (current is ContainerEventHandler && current.focused != null) current =
            current.focused
        return current
    }

    private fun party(): LinearLayout {
        val view =
            FinderPartyView(
                MithrilPF.finder,
                layout,
                navigation,
                ::rebuildPreservingScroll,
                {
                    MithrilPF.finder.state?.party?.let { party ->
                        editor = FinderDraft(party.floor, party)
                        editing = true
                        navigation.tab = FinderTab.PARTIES
                        rebuildWidgets()
                    }
                },
                { question, action ->
                    minecraft.setScreen(
                        ConfirmScreen(
                            { accepted ->
                                minecraft.setScreen(this)
                                if (accepted) action()
                            },
                            question,
                            text("party.confirm_detail"),
                        )
                    )
                },
            )
        bindings += view::refreshMessages
        return view.build()
    }

    private fun records(): LinearLayout {
        val data = RecordsData(DungeonTimers.records(), DungeonTimers.splitDefinitions)
        val floor = navigation.recordsFloor(DungeonTimers.lastFloor, data::hasPbs)
        floorsWithPbs = FloorPicker.FLOORS.filter(data::hasPbs).toSet()
        val w = layout.pageWidth
        val view = navigation.recordsView
        return LinearLayout.vertical().spacing(8).apply {
            val controls = addChild(LinearLayout.horizontal().spacing(6))
            floorButton =
                controls.addChild(
                    FlatButton(0, 0, FLOOR_BUTTON, floorLabel(floor)) {
                        floorListOpen = !floorListOpen
                        floorButton?.message = floorLabel(floor)
                    }
                )
            val views = addChild(LinearLayout.horizontal().spacing(4))
            for (option in RecordsView.entries) views.addChild(
                FlatButton(0, 0, 72, text("records.view.${option.key}"), option == view) {
                    navigation.recordsView = option
                    rebuildWidgets()
                }
            )
            when (view) {
                RecordsView.OVERVIEW -> overview(this, floor, data)
                RecordsView.SPLITS -> {
                    addChild(
                        SplitTable(w, data.splits(floor), w >= SplitTable.MIN_COLUMNS) { row ->
                            if (row.kind == SplitKind.SOLO) text("records.solo_300")
                            else Component.literal(DungeonTimers.splitName(row.name))
                        }
                    )
                    paragraph(this, text("records.splits_note"))
                }
                RecordsView.ROOMS -> rooms(this, controls, floor, data)
            }
        }
    }

    private fun shownFloor() =
        navigation.recordsFloor(DungeonTimers.lastFloor) { it in floorsWithPbs }

    private fun floorLabel(floor: String = shownFloor()) =
        text("records.floor", floor).append(if (floorListOpen) " ▴" else " ▾")

    private fun overview(page: LinearLayout, floor: String, data: RecordsData) {
        val cards =
            page.addChild(
                if (layout.wide) LinearLayout.horizontal().spacing(12)
                else LinearLayout.vertical().spacing(8)
            )
        val w = layout.columnWidth
        val stats = MithrilPF.finder.state?.stats
        val classes =
            DungeonRole.entries.joinToString(" · ") {
                "${it.short} ${metricText(FinderMetric.CLASS, stats?.roles?.get(it))}"
            }
        val metrics =
            FinderMetric.entries
                .filter { it != FinderMetric.CLASS }
                .map {
                    CardLine.Value(
                        text("metric.${it.key}"),
                        Component.literal(
                            metricText(it, stats?.value(it, DungeonRole.MAGE, floor))
                        ),
                    )
                }
        cards.addChild(
            RecordsCard(
                w,
                listOf(
                    CardLine.Heading(text("records.eligible"), text("records.eligible_from")),
                    CardLine.Rule,
                ) +
                    metrics +
                    listOf(
                        CardLine.Rule,
                        CardLine.Value(text("metric.class_level"), Component.literal(classes)),
                        CardLine.Text(
                            if (floor == "F7" || floor == "M7") text("records.requirement_floors")
                            else text("records.no_requirements", floor)
                        ),
                    ),
            )
        )
        fun local(best: DungeonBest?): Component =
            when {
                !DungeonTimers.ready -> text("records.unavailable")
                best == null -> Component.literal("—")
                else -> DungeonTimeFormat.styledPair(RecordsData.time(best), Palette.TEXT)
            }
        cards.addChild(
            RecordsCard(
                w,
                listOf(
                    CardLine.Heading(text("records.local"), text("records.local_from")),
                    CardLine.Rule,
                    CardLine.Value(
                        text("records.solo_clear"),
                        local(data.best("solo", floor, RecordsData.SOLO)),
                    ),
                    CardLine.Value(
                        text("records.terminals"),
                        local(data.best("splits", floor, "Terminals")),
                    ),
                    CardLine.Rule,
                    CardLine.Text(
                        Component.translatable("screen.mithrilpf.sync.${MithrilPF.syncStatus}"),
                        if (MithrilPF.syncStatus == "synced") Palette.SUCCESS else Palette.MUTED,
                    ),
                    CardLine.Text(text("records.local_help")),
                    CardLine.Text(text("records.more"), Palette.ACCENT),
                ),
            )
        )
    }

    private fun rooms(
        page: LinearLayout,
        controls: LinearLayout,
        floor: String,
        data: RecordsData,
    ) {
        val w = layout.pageWidth
        val columns = RoomColumns.fit(w)
        val searchWidth = minOf(184, w - FLOOR_BUTTON - 12)
        controls.addChild(
            SpacerElement.width((w - FLOOR_BUTTON - searchWidth - 12).coerceAtLeast(0))
        )
        val search = controls.addChild(EditBox(font, searchWidth, 20, text("records.search")))
        if (!columns.both) {
            val styles = page.addChild(LinearLayout.horizontal().spacing(4))
            for (midclear in listOf(true, false)) styles.addChild(
                FlatButton(
                    0,
                    0,
                    72,
                    text(if (midclear) "records.column.midclear" else "records.column.regular"),
                    navigation.recordsMidclear == midclear,
                ) {
                    navigation.recordsMidclear = midclear
                    rebuildPreservingScroll()
                }
            )
        }
        val table =
            page.addChild(
                RoomTable(
                    w,
                    columns,
                    navigation.recordsMidclear,
                    data.rooms(floor, navigation.recordsQuery),
                    {
                        if (navigation.recordsQuery.isBlank()) text("records.no_rooms", floor)
                        else text("records.no_match")
                    },
                ) {
                    !floorListOpen
                }
            )
        paragraph(page, text("records.rooms_note"))
        search.setMaxLength(32)
        search.setHint(
            text("records.search").withStyle { it.withColor(Palette.MUTED and 0xFFFFFF) }
        )
        search.value = navigation.recordsQuery
        search.setResponder {
            navigation.recordsQuery = it
            table.rows = data.rooms(floor, it)
            scroll.arrangeElements()
        }
    }

    /** The open floor list, drawn over the page below (or above) the floor button. */
    private fun floorList(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val button = floorButton ?: return
        val picker = FloorPicker.below(button.x, button.y, button.height, height)
        val shown = shownFloor()
        g.nextStratum()
        val right = picker.x + FloorPicker.WIDTH
        val bottom = picker.y + FloorPicker.HEIGHT
        g.fill(picker.x, picker.y, right, bottom, Palette.BORDER)
        g.fill(picker.x + 1, picker.y + 1, right - 1, bottom - 1, Palette.SURFACE_RAISED)
        g.fill(picker.x + 6, picker.y + 22, right - 6, picker.y + 23, Palette.BORDER)
        val headerY = picker.y + FloorPicker.HEADERS
        val column = (FloorPicker.WIDTH - 12) / 2
        g.text(font, text("records.catacombs"), picker.x + 8, headerY, Palette.MUTED, false)
        g.text(font, text("records.master"), picker.x + 12 + column, headerY, Palette.MUTED, false)
        val hovered = picker.floorAt(mouseX.toDouble(), mouseY.toDouble())
        for (floor in FloorPicker.FLOORS) {
            val cell = picker.cell(floor) ?: continue
            val selected = floor == shown
            if (selected || floor == hovered)
                g.fill(
                    cell.x,
                    cell.y + 1,
                    cell.x + cell.width,
                    cell.y + cell.height - 1,
                    if (selected) Palette.PRIMARY else Palette.SECONDARY_HOVER,
                )
            val label = if (floor == "E") text("records.entrance") else Component.literal(floor)
            val color =
                when {
                    selected -> Palette.PRIMARY_TEXT
                    floor in floorsWithPbs -> Palette.TEXT
                    else -> Palette.MUTED
                }
            val x = if (floor == "E") cell.x + (cell.width - font.width(label)) / 2 else cell.x + 4
            g.text(font, label, x, cell.y + 4, color, false)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val button = floorButton
        if (!floorListOpen || button == null) return super.mouseClicked(event, doubled)
        // Any click closes the list; one on a floor also picks it.
        floorListOpen = false
        val floor =
            FloorPicker.below(button.x, button.y, button.height, height)
                .floorAt(event.x(), event.y())
        if (floor != null) {
            navigation.chooseRecordsFloor(floor)
            rebuildWidgets()
        } else button.message = floorLabel()
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (floorListOpen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            floorListOpen = false
            floorButton?.message = floorLabel()
            return true
        }
        return super.keyPressed(event)
    }

    private fun settings(): LinearLayout {
        val columns =
            if (layout.wide) LinearLayout.horizontal().spacing(12)
            else LinearLayout.vertical().spacing(14)
        val left = columns.addChild(LinearLayout.vertical().spacing(8))
        val right = if (layout.wide) columns.addChild(LinearLayout.vertical().spacing(8)) else left
        val w = layout.columnWidth
        paragraph(right, text("settings.finder"), w, false)
        toggle(
            right,
            w,
            text("settings.finder_sound"),
            text("settings.finder_sound.help"),
            { DungeonTimers.settings.finderSound },
            { DungeonTimers.ready },
        ) {
            DungeonTimers.update(DungeonTimers.settings.copy(finderSound = it))
        }
        toggle(
            right,
            w,
            text("settings.finder_hud"),
            text("settings.finder_hud.help"),
            { DungeonTimers.settings.finderHud },
            { DungeonTimers.ready },
        ) {
            DungeonTimers.update(DungeonTimers.settings.copy(finderHud = it))
        }
        paragraph(left, text("settings.account"), w, false)
        accountActions(left, w)
        paragraph(left, Component.translatable("tracking.mithrilpf.title"), w, false)
        action(left, "tracking.mithrilpf.edit", w, enabled = { DungeonTimers.ready }) {
            minecraft.setScreen(TrackingHudEditor(this))
        }
        tracking(left, "enabled", { it.enabled }, { s, v -> s.copy(enabled = v) })
        tracking(left, "solo", { it.solo }, { s, v -> s.copy(solo = v) })
        tracking(left, "rooms", { it.rooms }, { s, v -> s.copy(rooms = v) })
        tracking(left, "table", { it.table }, { s, v -> s.copy(table = v) })
        tracking(left, "split", { it.split }, { s, v -> s.copy(split = v) })
        tracking(left, "ticks", { it.ticks }, { s, v -> s.copy(ticks = v) })
        tracking(left, "dungeon_only", { it.dungeonOnly }, { s, v -> s.copy(dungeonOnly = v) })
        tracking(left, "paul", { it.paul }, { s, v -> s.copy(paul = v) })
        paragraph(right, text("settings.presence"), w, false)
        toggle(
            right,
            w,
            text("settings.discord"),
            text("settings.discord.help"),
            { DungeonTimers.settings.discordPresence },
            { DungeonTimers.ready },
        ) {
            DungeonTimers.update(DungeonTimers.settings.copy(discordPresence = it))
        }
        paragraph(right, Component.translatable("update.mithrilpf.title"), w, false)
        val updates = MithrilPF.updates
        toggle(
            right,
            w,
            Component.translatable("update.mithrilpf.enabled"),
            text("settings.updates.help"),
            { updates.status.settings.enabled },
            { updates.status.loaded },
        ) {
            updates.configure(updates.status.settings.copy(enabled = it, asked = true))
        }
        toggle(
            right,
            w,
            Component.translatable("update.mithrilpf.prereleases"),
            text("settings.prereleases.help"),
            { updates.status.settings.prereleases },
            { updates.status.loaded },
        ) {
            updates.configure(updates.status.settings.copy(prereleases = it, asked = true))
        }
        dynamic(right, w) {
            Component.translatable(
                "update.mithrilpf.${updates.status.state}",
                updates.status.version,
                updates.status.unmet.joinToString(", "),
            )
        }
        val check =
            action(
                right,
                "update.mithrilpf.check",
                w,
                enabled = {
                    updates.status.let {
                        updates.cooldownSeconds == 0L &&
                            it.loaded &&
                            it.settings.enabled &&
                            it.state !in setOf("checking", "downloading", "ready", "local")
                    }
                },
            ) {
                updates.checkNow()
            }
        action(right, "update.mithrilpf.review", w, enabled = { updates.status.release != null }) {
            minecraft.setScreen(UpdatePromptScreen(this, updates))
        }
        bindings += {
            check.message =
                if (
                    updates.cooldownSeconds > 0 &&
                        updates.status.state !in setOf("checking", "downloading", "ready", "local")
                )
                    text("settings.check_wait", updates.cooldownSeconds)
                else Component.translatable("update.mithrilpf.check")
            false
        }
        return columns
    }

    private fun accountActions(page: LinearLayout, w: Int = layout.pageWidth) {
        val native = MithrilPF.nativeAccount
        dynamic(page, w) { text("account.${native.status}") }
        val signIn =
            action(
                page,
                "finder.mithrilpf.account.sign_in",
                w,
                primary = true,
                enabled = { !native.busy },
            ) {
                when {
                    native.session != null -> native.signOut()
                    !native.writable -> native.load()
                    else -> native.signIn()
                }
            }
        bindings += {
            signIn.message =
                text(
                    if (native.session != null) "account.sign_out"
                    else if (!native.writable) "account.retry" else "account.sign_in"
                )
            false
        }
        dynamic(page, w) {
            Component.translatable(
                "screen.mithrilpf.link.${browserLink.status}",
                minecraft.user.name,
            )
        }
        val button =
            action(page, "screen.mithrilpf.link", w, enabled = { !browserLink.working }) {
                minecraft.setScreen(LinkScreen(this, browserLink))
            }
        bindings += {
            button.message =
                Component.translatable(
                    if (browserLink.linked) "screen.mithrilpf.relink" else "screen.mithrilpf.link"
                )
            false
        }
    }

    private fun tracking(
        page: LinearLayout,
        key: String,
        read: (TrackingSettings) -> Boolean,
        write: (TrackingSettings, Boolean) -> TrackingSettings,
    ) {
        toggle(
            page,
            layout.columnWidth,
            Component.translatable("tracking.mithrilpf.$key"),
            text("settings.$key.help"),
            { read(DungeonTimers.settings) },
            { DungeonTimers.ready },
        ) {
            DungeonTimers.update(write(DungeonTimers.settings, it))
        }
    }

    private fun toggle(
        page: LinearLayout,
        w: Int,
        label: Component,
        description: Component,
        value: () -> Boolean,
        enabled: () -> Boolean,
        change: (Boolean) -> Unit,
    ) {
        val toggle = page.addChild(SettingToggle(w, label, description, value, enabled, change))
        bindings += {
            toggle.refresh()
            false
        }
    }

    private fun paragraph(
        page: LinearLayout,
        value: Component,
        w: Int = layout.pageWidth,
        muted: Boolean = true,
    ): MultiLineTextWidget =
        page.addChild(
            MultiLineTextWidget(
                    value.copy().withStyle {
                        it.withColor((if (muted) Palette.MUTED else Palette.TEXT) and 0xFFFFFF)
                    },
                    font,
                )
                .setMaxWidth(w)
        )

    private fun dynamic(
        page: LinearLayout,
        w: Int = layout.pageWidth,
        muted: Boolean = true,
        value: () -> Component,
    ) {
        var previous = value()
        val widget = paragraph(page, previous, w, muted)
        bindings += {
            val next = value()
            if (next == previous) false
            else {
                previous = next
                widget.message =
                    next.copy().withStyle {
                        it.withColor((if (muted) Palette.MUTED else Palette.TEXT) and 0xFFFFFF)
                    }
                true
            }
        }
    }

    private fun action(
        page: LinearLayout,
        key: String,
        w: Int = layout.pageWidth,
        primary: Boolean = false,
        enabled: () -> Boolean = { true },
        action: () -> Unit,
    ): FlatButton {
        val button =
            page.addChild(FlatButton(0, 0, w, Component.translatable(key), primary, action))
        button.active = enabled()
        bindings += {
            button.active = enabled()
            false
        }
        return button
    }

    override fun tick() {
        browserLink.tick()
        if (account != minecraft.user.profileId.toString()) {
            rebuildWidgets()
            return
        }
        if (++refreshTicks % 5 != 0) return
        val party = MithrilPF.finder.state?.party
        val credentialsChanged = displayedToken != MithrilPF.nativeAccount.session?.token
        if (
            credentialsChanged ||
                (editing &&
                    editor != null &&
                    (party?.id != editor?.partyId || party?.youLead != true))
        ) {
            editor = null
            editing = false
            rebuildWidgets()
            return
        }
        val membershipChanged = displayedMembership != (party?.id to party?.youLead)
        if (
            editor == null &&
                (membershipChanged || focusedLeaf() !is EditBox) &&
                viewStamp != currentStamp()
        ) {
            rebuildPreservingScroll()
            return
        }
        refreshBindings()
    }

    private fun refreshBindings() {
        var changed = false
        for (binding in bindings) if (binding()) changed = true
        if (changed) scroll.arrangeElements()
    }

    /**
     * Rebuilds for new data or a click without losing the reader's place: every scroll area keeps
     * its position, and focus stays on the same control when the page has the same controls. A
     * callback finishing after the screen closed changes nothing.
     */
    private fun rebuildPreservingScroll() {
        if (minecraft.screen !== this) return
        // An area scrolled to its end (such as chat at the newest message) stays at its end.
        val amounts =
            scrollAreas().map {
                if (it.maxScrollAmount() > 0 && it.scrollAmount() >= it.maxScrollAmount())
                    Double.MAX_VALUE
                else it.scrollAmount()
            }
        val before = leaves()
        val focus = focusedLeaf()?.takeUnless { it is FinderTabButton }?.let(before::indexOf) ?: -1
        rebuildWidgets()
        val after = leaves()
        if (focus >= 0 && after.size == before.size) pathTo(after[focus], this)?.let(::changeFocus)
        scrollAreas().forEachIndexed { i, area ->
            amounts.getOrNull(i)?.let {
                area.setScrollAmount(it.coerceAtMost(area.maxScrollAmount().toDouble()))
            }
        }
    }

    private fun scrollAreas(): List<AbstractScrollArea> =
        descendants(this).filterIsInstance<AbstractScrollArea>()

    private fun leaves(): List<GuiEventListener> =
        descendants(this).filter { it !is ContainerEventHandler }

    private fun descendants(parent: ContainerEventHandler): List<GuiEventListener> =
        parent.children().flatMap { child ->
            listOf(child) + ((child as? ContainerEventHandler)?.let(::descendants) ?: emptyList())
        }

    private fun pathTo(target: GuiEventListener, parent: ContainerEventHandler): ComponentPath? {
        for (child in parent.children()) {
            if (child === target) return ComponentPath.path(parent, ComponentPath.leaf(child))
            if (child is ContainerEventHandler)
                pathTo(target, child)?.let {
                    return ComponentPath.path(parent, it)
                }
        }
        return null
    }

    // Vanilla gives the wheel to the outer scroll area only, so lists inside it never scrolled.
    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean =
        innermostScroll(this, mouseX, mouseY)?.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
            ?: super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)

    private fun innermostScroll(
        parent: ContainerEventHandler,
        x: Double,
        y: Double,
    ): AbstractScrollArea? {
        for (child in parent.children()) {
            if (!child.isMouseOver(x, y)) continue
            (child as? ContainerEventHandler)
                ?.let { innermostScroll(it, x, y) }
                ?.let {
                    return it
                }
            if (child is AbstractScrollArea && child.maxScrollAmount() > 0) return child
        }
        return null
    }

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        g.fill(0, 0, width, height, Palette.SCRIM)
        val p = layout.panel
        g.fill(p.x, p.y, p.x + p.width, p.y + p.height, Palette.BORDER)
        g.fill(p.x + 1, p.y + 1, p.x + p.width - 1, p.y + p.height - 1, Palette.BACKGROUND)
        g.text(font, Component.literal("MithrilPF"), layout.contentX, p.y + 9, Palette.TEXT, false)
        val versionText = Component.literal(if (layout.wide) minecraft.user.name else version)
        g.text(
            font,
            versionText,
            p.x + p.width - 8 - font.width(versionText),
            p.y + 9,
            Palette.MUTED,
            false,
        )
        if (layout.wide) {
            val finder = MithrilPF.finder
            val status =
                text(
                    when {
                        MithrilPF.nativeAccount.session == null -> "header.signed_out"
                        finder.offline -> "header.offline"
                        finder.state?.inGame == true -> "header.in_game"
                        else -> "header.not_in_game"
                    }
                )
            val available = (layout.contentWidth - 400 - font.width(versionText)).coerceAtLeast(0)
            val shown = Language.getInstance().getVisualOrder(font.substrByWidth(status, available))
            g.text(
                font,
                shown,
                p.x + p.width - 20 - font.width(versionText) - font.width(shown),
                p.y + 9,
                if (finder.state?.inGame == true && !finder.offline) Palette.SUCCESS
                else Palette.MUTED,
                false,
            )
        }
        if (
            navigation.tab == FinderTab.PARTIES &&
                editor == null &&
                layout.wide &&
                MithrilPF.nativeAccount.session != null
        ) {
            val divider = layout.contentX + 10 + (layout.pageWidth * 0.61).toInt() + 4
            g.fill(
                divider,
                layout.contentY,
                divider + 1,
                layout.contentY + layout.contentHeight,
                Palette.BORDER,
            )
        }
        g.fill(p.x + 1, layout.contentY - 8, p.x + p.width - 1, layout.contentY - 7, Palette.BORDER)
        g.fill(p.x + 1, p.y + p.height - 22, p.x + p.width - 1, p.y + p.height - 21, Palette.BORDER)
        val hint = font.substrByWidth(text("keys"), layout.contentWidth)
        g.text(
            font,
            Language.getInstance().getVisualOrder(hint),
            layout.contentX,
            p.y + p.height - 15,
            Palette.MUTED,
            false,
        )
        super.extractRenderState(g, mouseX, mouseY, delta)
        if (floorListOpen) floorList(g, mouseX, mouseY)
        val errorKey =
            MithrilPF.finder.error?.let { "error.$it" }
                ?: if (MithrilPF.finder.presetError) "editor.preset_error" else null
        errorKey?.let { error ->
            g.fill(
                p.x + 1,
                p.y + p.height - 21,
                p.x + p.width - 1,
                p.y + p.height - 1,
                Palette.BACKGROUND,
            )
            val message = ellipsize(text(error).string, layout.contentWidth, font::width)
            g.text(
                font,
                Language.getInstance().getVisualOrder(FormattedText.of(message)),
                layout.contentX,
                p.y + p.height - 15,
                Palette.DANGER,
                false,
            )
        }
    }

    private fun text(key: String, vararg args: Any) =
        Component.translatable("finder.mithrilpf.$key", *args)

    override fun onClose() {
        minecraft.setScreen(parent)
    }

    override fun removed() {
        browserLink.cancel()
    }

    override fun isPauseScreen() = false
}

private const val FLOOR_BUTTON = 84

/** One line of a Records Overview card. */
private sealed interface CardLine {
    data class Heading(val title: Component, val subtitle: Component) : CardLine

    data object Rule : CardLine

    data class Value(val label: Component, val value: Component) : CardLine

    data class Text(val value: Component, val color: Int = Palette.MUTED) : CardLine
}

/** A bordered card of labelled values and wrapped notes; its height fits its lines. */
private class RecordsCard(width: Int, private val lines: List<CardLine>) :
    AbstractWidget(0, 0, width, 0, Component.empty()) {
    private val font = Minecraft.getInstance().font
    private val wrapped = lines.associateWith { line ->
        if (line is CardLine.Text) font.split(line.value, (width - 24).coerceAtLeast(1))
        else emptyList()
    }

    init {
        active = false
        height = 12 + lines.sumOf(::lineHeight)
    }

    private fun lineHeight(line: CardLine) =
        when (line) {
            is CardLine.Heading -> 28
            CardLine.Rule -> 9
            is CardLine.Value -> 14
            is CardLine.Text -> wrapped.getValue(line).size * font.lineHeight + 6
        }

    override fun extractWidgetRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        g.fill(x, y, right, bottom, Palette.BORDER)
        g.fill(x + 1, y + 1, right - 1, bottom - 1, Palette.SURFACE)
        var lineY = y + 8
        for (line in lines) {
            when (line) {
                is CardLine.Heading -> {
                    g.text(font, line.title, x + 12, lineY, Palette.TEXT, false)
                    g.text(font, line.subtitle, x + 12, lineY + 13, Palette.MUTED, false)
                }
                CardLine.Rule -> g.fill(x + 12, lineY + 3, right - 12, lineY + 4, Palette.BORDER)
                is CardLine.Value -> {
                    val valueWidth = font.width(line.value)
                    val label = ellipsize(line.label.string, width - 36 - valueWidth, font::width)
                    g.text(font, Component.literal(label), x + 12, lineY + 2, Palette.MUTED, false)
                    g.text(
                        font,
                        line.value,
                        right - 12 - valueWidth,
                        lineY + 2,
                        Palette.TEXT,
                        false,
                    )
                }
                is CardLine.Text ->
                    wrapped.getValue(line).forEachIndexed { index, text ->
                        g.text(
                            font,
                            text,
                            x + 12,
                            lineY + 3 + index * font.lineHeight,
                            line.color,
                            false,
                        )
                    }
            }
            lineY += lineHeight(line)
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(
            NarratedElementType.TITLE,
            Component.literal(
                lines.joinToString(". ") {
                    when (it) {
                        is CardLine.Heading -> it.title.string
                        CardLine.Rule -> ""
                        is CardLine.Value -> "${it.label.string}: ${it.value.string}"
                        is CardLine.Text -> it.value.string
                    }
                }
            ),
        )
    }
}

/** Every split PB for a floor, real time with the tick time in grey; two columns when wide. */
private class SplitTable(
    width: Int,
    private val rows: List<SplitRow>,
    private val twoColumns: Boolean,
    private val label: (SplitRow) -> Component,
) : AbstractWidget(0, 0, width, 0, Component.empty()) {
    private val font = Minecraft.getInstance().font
    private val perColumn = if (twoColumns) (rows.size + 1) / 2 else rows.size

    init {
        active = false
        height = perColumn * ROW
    }

    override fun extractWidgetRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        val columnWidth = if (twoColumns) (width - 8) / 2 else width
        rows.forEachIndexed { index, row ->
            val column = index / perColumn.coerceAtLeast(1)
            val line = index % perColumn.coerceAtLeast(1)
            val left = x + column * (columnWidth + 8)
            val top = y + line * ROW
            if (line % 2 == 0) g.fill(left, top, left + columnWidth, top + ROW, Palette.SURFACE)
            val value =
                row.best?.let { DungeonTimeFormat.styledPair(RecordsData.time(it), Palette.TEXT) }
                    ?: Component.literal("—")
            val valueWidth = font.width(value)
            val name = ellipsize(label(row).string, columnWidth - 24 - valueWidth, font::width)
            val nameColor =
                when (row.kind) {
                    SplitKind.SOLO -> Palette.ACCENT
                    else -> Palette.TEXT
                }
            g.text(font, Component.literal(name), left + 8, top + 5, nameColor, false)
            g.text(
                font,
                value,
                left + columnWidth - 8 - valueWidth,
                top + 5,
                if (row.best == null) Palette.MUTED else Palette.TEXT,
                false,
            )
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(
            NarratedElementType.TITLE,
            Component.literal(
                rows.joinToString(". ") {
                    "${label(it).string}: ${it.best?.let { best -> DungeonTimeFormat.pair(RecordsData.time(best)) } ?: "—"}"
                }
            ),
        )
    }

    companion object {
        const val ROW = 18
        const val MIN_COLUMNS = 440
    }
}

/**
 * Room PBs, one row per room. Times show real time; hovering a value shows its tick time. Narrow
 * tables show one style's clear and secrets, picked by [midclear].
 */
private class RoomTable(
    width: Int,
    private val columns: RoomColumns,
    private val midclear: Boolean,
    rows: List<RoomRow>,
    private val empty: () -> Component,
    private val hover: () -> Boolean,
) : AbstractWidget(0, 0, width, 0, Component.empty()) {
    private val font = Minecraft.getInstance().font

    var rows = rows
        set(value) {
            field = value
            height = HEADER + value.size.coerceAtLeast(1) * ROW
        }

    init {
        active = false
        this.rows = rows
    }

    private fun headers() =
        if (columns.both) listOf("midclear", "regular", "secrets_mid", "secrets_reg", "total")
        else listOf("clear", "secrets", "total")

    /** Each value column's text, colour and tooltip for a row. */
    private fun cells(row: RoomRow): List<Triple<String, Int, Component?>?> {
        fun time(best: DungeonBest?) =
            best?.let {
                Triple(
                    DungeonTimeFormat.millis(it.realMillis),
                    Palette.TEXT,
                    finderText("records.tick_time", DungeonTimeFormat.ticks(it.ticks)),
                )
            } ?: DASH
        fun rate(best: DungeonBest?) =
            best?.let {
                Triple(
                    RecordsData.rate(it.realMillis),
                    Palette.TEXT,
                    finderText("records.tick_rate", RecordsData.rate(it.ticks * 50)),
                )
            } ?: DASH
        val none = finderText(if (columns.both) "records.no_secrets" else "records.none").string
        return if (columns.both) {
            if (row.noSecrets)
                listOf(
                    DASH,
                    time(row.regular),
                    Triple(none, Palette.MUTED, null),
                    null,
                    time(row.total),
                )
            else
                listOf(
                    time(row.midclear),
                    time(row.regular),
                    rate(row.secretsMid),
                    rate(row.secretsReg),
                    time(row.total),
                )
        } else if (row.noSecrets)
            listOf(time(row.regular), Triple(none, Palette.MUTED, null), time(row.total))
        else if (midclear) listOf(time(row.midclear), rate(row.secretsMid), time(row.total))
        else listOf(time(row.regular), rate(row.secretsReg), time(row.total))
    }

    override fun extractWidgetRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        g.text(font, finderText("records.column.room"), x + 8, y + 2, Palette.MUTED, false)
        headers().forEachIndexed { index, key ->
            val header = finderText("records.column.$key")
            g.text(
                font,
                header,
                x + columns.rights[index] - font.width(header),
                y + 2,
                Palette.MUTED,
                false,
            )
        }
        g.fill(x, y + HEADER - 3, right, y + HEADER - 2, Palette.BORDER)
        if (rows.isEmpty()) {
            g.text(font, empty(), x + 8, y + HEADER + 5, Palette.MUTED, false)
            return
        }
        var tooltip: Component? = null
        rows.forEachIndexed { index, row ->
            val top = y + HEADER + index * ROW
            if (index % 2 == 0) g.fill(x, top, right, top + ROW, Palette.SURFACE)
            val name = ellipsize(row.name, columns.nameWidth, font::width)
            g.text(font, Component.literal(name), x + 8, top + 5, Palette.TEXT, false)
            cells(row).forEachIndexed { column, cell ->
                if (cell == null) return@forEachIndexed
                val (text, color, tip) = cell
                val textWidth = font.width(text)
                val left = x + columns.rights[column] - textWidth
                g.text(font, Component.literal(text), left, top + 5, color, false)
                if (
                    tip != null &&
                        isHovered &&
                        mouseY in top until top + ROW &&
                        mouseX in left until left + textWidth
                )
                    tooltip = tip
            }
        }
        if (hover()) tooltip?.let { g.setTooltipForNextFrame(font, it, mouseX, mouseY) }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(
            NarratedElementType.TITLE,
            Component.literal(
                if (rows.isEmpty()) empty().string
                else
                    rows.joinToString(". ") { row ->
                        val values =
                            headers().zip(cells(row)).mapNotNull { (key, cell) ->
                                cell?.let {
                                    "${finderText("records.column.$key").string} ${it.first}"
                                }
                            }
                        "${row.name}: ${values.joinToString(", ")}"
                    }
            ),
        )
    }

    companion object {
        const val ROW = 18
        const val HEADER = 14
        private val DASH = Triple("—", Palette.MUTED, null as Component?)
    }
}
