package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.MithrilPF
import dev.mithril.mithrilpf.account.BrowserLink
import dev.mithril.mithrilpf.dungeontimer.DungeonTimeFormat
import dev.mithril.mithrilpf.dungeontimer.DungeonTimers
import dev.mithril.mithrilpf.dungeontimer.TrackingSettings
import dev.mithril.mithrilpf.finder.DungeonRole
import dev.mithril.mithrilpf.finder.FinderMetric
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractScrollArea
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component

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
            layout.panel.y + if (layout.wide) 2 else 26,
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
                                ::rebuildWidgets,
                                {
                                    editor = null
                                    rebuildWidgets()
                                },
                                {
                                    editor = null
                                    navigation.tab = FinderTab.PARTY
                                    rebuildWidgets()
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
                ::rebuildWidgets,
                {
                    editing = false
                    finderDraft(MithrilPF.finder.floor)
                    rebuildWidgets()
                },
                {
                    navigation.tab = FinderTab.PARTY
                    rebuildWidgets()
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
                ::rebuildWidgets,
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

    private fun records() =
        LinearLayout.vertical().spacing(10).apply {
            paragraph(this, text("records.eligible"), muted = false)
            val floors = LinearLayout.horizontal().spacing(6)
            for (floor in listOf("F7", "M7")) {
                floors.addChild(
                    FlatButton(
                        0,
                        0,
                        54,
                        Component.literal(floor),
                        primary = navigation.floor == floor,
                    ) {
                        navigation.floor = floor
                        rebuildWidgets()
                    }
                )
            }
            addChild(floors)
            for (metric in FinderMetric.entries.filter { it != FinderMetric.CLASS }) dynamic(
                this,
                muted = false,
            ) {
                text(
                    "records.value",
                    text("metric.${metric.key}"),
                    Component.literal(
                        metricText(
                            metric,
                            MithrilPF.finder.state
                                ?.stats
                                ?.value(metric, DungeonRole.MAGE, navigation.floor),
                        )
                    ),
                )
            }
            val roles = addChild(LinearLayout.horizontal().spacing(4))
            for (role in DungeonRole.entries) {
                dynamic(roles, (layout.pageWidth - 16) / 5) {
                    Component.literal(
                        "${role.short}: ${metricText(FinderMetric.CLASS, MithrilPF.finder.state?.stats?.roles?.get(role))}"
                    )
                }
            }
            paragraph(this, text("records.local"), muted = false)
            for (kind in listOf("solo_clear", "terminals")) dynamic(this, muted = false) {
                val best =
                    DungeonTimers.syncSnapshot(account).records.firstOrNull {
                        it.floor == navigation.floor && it.kind == kind
                    }
                text(
                    "records.value",
                    text("records.$kind"),
                    when {
                        !DungeonTimers.ready -> text("records.unavailable")
                        best != null -> Component.literal(DungeonTimeFormat.millis(best.realMillis))
                        else -> text("records.empty")
                    },
                )
            }
            dynamic(this) {
                Component.translatable("screen.mithrilpf.sync.${MithrilPF.syncStatus}")
            }
            paragraph(this, text("records.local_help"))
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

    private fun rebuildPreservingScroll() {
        var amount = 0.0
        scroll.visitWidgets { if (it is AbstractScrollArea) amount = it.scrollAmount() }
        rebuildWidgets()
        scroll.visitWidgets { if (it is AbstractScrollArea) it.setScrollAmount(amount) }
    }

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
        val errorKey =
            MithrilPF.finder.error?.let { "error.$it" }
                ?: if (MithrilPF.finder.presetError) "editor.preset_error" else null
        errorKey?.let { error ->
            g.fill(
                layout.contentX,
                p.y + p.height - 22,
                p.x + p.width - 8,
                p.y + p.height - 1,
                Palette.BACKGROUND,
            )
            g.text(
                font,
                Language.getInstance()
                    .getVisualOrder(font.substrByWidth(text(error), layout.contentWidth)),
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
