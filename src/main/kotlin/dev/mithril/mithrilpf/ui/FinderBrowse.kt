package dev.mithril.mithrilpf.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.mithril.mithrilpf.finder.DungeonRole
import dev.mithril.mithrilpf.finder.FinderClient
import dev.mithril.mithrilpf.finder.FinderMetric
import dev.mithril.mithrilpf.finder.FinderParty
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.network.chat.Component

class FinderBrowse(
    private val finder: FinderClient,
    private val layout: FinderLayout,
    private val navigation: FinderNavigation,
    private val rebuild: () -> Unit,
    private val create: () -> Unit,
    private val joined: () -> Unit,
) {
    fun build(): LinearLayout {
        if (!layout.wide && finder.selected != null) return compactDetail()
        val root =
            if (layout.wide) LinearLayout.horizontal().spacing(8)
            else LinearLayout.vertical().spacing(10)
        val leftWidth = if (layout.wide) (layout.pageWidth * 0.61).toInt() else layout.pageWidth
        val left = root.addChild(LinearLayout.vertical().spacing(7))
        val filters = filters(left)
        searchControls(left, filters, leftWidth)
        toolbar(left, leftWidth)
        listings(left, leftWidth)
        val rightWidth = if (layout.wide) layout.pageWidth - leftWidth - 8 else leftWidth
        val right = LinearLayout.vertical().spacing(9)
        val detailWidth = if (layout.wide) rightWidth - 20 else rightWidth
        val party = finder.detail ?: finder.listings.firstOrNull { it.id == finder.selected }
        if (party == null)
            right.label(
                finderText(if (finder.selected == null) "browse.select" else "browse.gone"),
                detailWidth,
                Palette.MUTED,
            )
        else detail(right, party, detailWidth)
        if (layout.wide)
            root
                .addChild(ScrollableLayout(Minecraft.getInstance(), right, layout.contentHeight))
                .setMinWidth(detailWidth)
        return root
    }

    private fun eligible(party: FinderParty): Set<DungeonRole> =
        party.slots
            .filter { slot ->
                !slot.filled &&
                    (navigation.roles.isEmpty() || slot.role in navigation.roles) &&
                    finder.state?.stats?.qualifies(party.rules, slot.role, party.floor) == true
            }
            .map { it.role }
            .toSet()

    private fun row(page: LinearLayout, party: FinderParty, width: Int) {
        page.addChild(
            FinderPartyRow(
                width,
                party,
                finder.selected == party.id,
                eligible(party),
                DungeonRole.entries
                    .filter { role ->
                        finder.state?.stats?.qualifies(party.rules, role, party.floor) == false
                    }
                    .toSet(),
            ) {
                finder.select(party.id)
                navigation.reserveRole = null
                rebuild()
            }
        )
    }

    private fun detail(page: LinearLayout, party: FinderParty, width: Int) {
        page.label(finderText("browse.party", party.leader), width)
        page.label(
            Component.literal(
                "${party.floor} · ${party.slots.count { it.filled }}/5 · S+ ${metricText(FinderMetric.S_PLUS, party.averageTime)}"
            ),
            width,
            Palette.MUTED,
        )
        val role = navigation.reserveRole
        comparisons(page, party, width, role)
        roster(page, party, width, role)
        val permitted =
            role != null &&
                party.slots.any { !it.filled && it.role == role } &&
                finder.state?.stats?.qualifies(party.rules, role, party.floor) == true
        page.button(
            finderText(
                "browse.reserve",
                role?.let { finderText("role.${it.key}") } ?: finderText("browse.slot"),
            ),
            width,
            primary = true,
            enabled = permitted && !finder.busy && finder.state?.party == null && !finder.offline,
        ) {
            finder.action(
                "reserve",
                JsonObject().apply {
                    addProperty("party_id", party.id)
                    addProperty("role", role!!.key)
                },
            ) { success ->
                if (success) joined()
            }
        }
    }

    private fun compactDetail(): LinearLayout {
        return LinearLayout.vertical().spacing(8).apply {
            button(finderText("editor.back"), 70) {
                finder.clearSelection()
                navigation.reserveRole = null
                rebuild()
            }
            val party = finder.detail ?: finder.listings.firstOrNull { it.id == finder.selected }
            if (party == null) label(finderText("browse.gone"), layout.pageWidth, Palette.MUTED)
            else detail(this, party, layout.pageWidth)
        }
    }

    private fun filters(left: LinearLayout): LinearLayout {
        val filters = left.addChild(LinearLayout.horizontal().spacing(3))
        for (floor in listOf("M7", "F7")) filters.button(
            Component.literal(floor),
            28,
            finder.floor == floor,
        ) {
            finder.changeFloor(floor)
            navigation.floor = floor
            rebuild()
        }
        for (role in DungeonRole.entries) filters.addChild(
            FinderRoleButton(23, role, role in navigation.roles) {
                navigation.roles =
                    if (role in navigation.roles) navigation.roles - role
                    else navigation.roles + role
                rebuild()
            }
        )

        return filters
    }

    private fun searchControls(left: LinearLayout, filters: LinearLayout, leftWidth: Int) {
        val searching = finder.state?.looking
        if (searching != null)
            left.label(
                finderText(
                    "browse.searching_as",
                    searching.floor,
                    searching.roles.joinToString(" / ") { finderText("role.${it.key}").string },
                ),
                leftWidth,
                Palette.ACCENT,
            )
        val inlineSearch = leftWidth >= 300
        val lookControls = if (inlineSearch) filters else left
        fun canLook() =
            !finder.busy &&
                finder.state?.party == null &&
                (searching != null ||
                    (navigation.roles.isNotEmpty() &&
                        runCatching { metricInput(FinderMetric.S_PLUS, navigation.maximumTime) }
                            .isSuccess))
        val look =
            lookControls.button(
                finderText(if (searching == null) "browse.look" else "browse.stop"),
                if (inlineSearch) leftWidth - 192 else leftWidth,
                primary = true,
                enabled = canLook(),
            ) {
                if (searching != null) finder.action("stop-looking", JsonObject())
                else
                    finder.action(
                        "look",
                        JsonObject().apply {
                            addProperty("version", 1)
                            addProperty("floor", finder.floor)
                            metricInput(FinderMetric.S_PLUS, navigation.maximumTime)?.let {
                                addProperty("max_team_s_plus_ms", it)
                            }
                            add(
                                "classes",
                                JsonArray().apply { for (role in navigation.roles) add(role.key) },
                            )
                        },
                    )
            }
        val limit = left.addChild(LinearLayout.horizontal().spacing(5))
        limit.label(finderText("browse.maximum"), leftWidth - 65, Palette.MUTED)
        limit
            .addChild(EditBox(Minecraft.getInstance().font, 60, 18, finderText("browse.maximum")))
            .apply {
                setMaxLength(6)
                value = navigation.maximumTime
                setHint(finderText("editor.time_hint"))
                setResponder {
                    navigation.maximumTime = it
                    look.active = canLook()
                }
            }
    }

    private fun toolbar(left: LinearLayout, leftWidth: Int) {
        val toolbar = left.addChild(LinearLayout.horizontal().spacing(4))
        toolbar.button(finderText("browse.sort.${navigation.sort}"), (leftWidth - 4) / 2) {
            navigation.sort = (navigation.sort + 1) % 3
            rebuild()
        }
        toolbar.button(
            finderText("browse.create"),
            (leftWidth - 4) / 2,
            enabled = finder.state?.party == null && !finder.busy && finder.presetsLoaded,
            action = create,
        )
        if (finder.presetError)
            left.label(finderText("editor.preset_error"), leftWidth, Palette.DANGER)
        if (finder.state == null)
            left.label(
                finderText(if (finder.offline) "error.unavailable" else "browse.loading"),
                leftWidth,
                Palette.MUTED,
            )
    }

    private fun listings(left: LinearLayout, leftWidth: Int) {
        val sorted =
            when (navigation.sort) {
                1 -> finder.listings.sortedByDescending { it.averageCata ?: -1.0 }
                2 -> finder.listings.sortedBy { it.rules.shared[FinderMetric.CATACOMBS] ?: 0 }
                else ->
                    finder.listings.sortedByDescending { party -> party.slots.count { it.filled } }
            }
        val groups = sorted.partition { eligible(it).isNotEmpty() }
        val rows = LinearLayout.vertical().spacing(5)
        val rowWidth = if (layout.wide) leftWidth - 20 else leftWidth
        rows.label(finderText("browse.available", groups.first.size), rowWidth, Palette.MUTED)
        for (party in groups.first) row(rows, party, rowWidth)
        if (finder.listings.isEmpty() && finder.state != null)
            rows.label(finderText("browse.empty"), rowWidth, Palette.MUTED)
        if (groups.second.isNotEmpty()) {
            rows.button(
                finderText(
                    if (navigation.showUnavailable) "browse.hide_unavailable"
                    else "browse.show_unavailable",
                    groups.second.size,
                ),
                rowWidth,
            ) {
                navigation.showUnavailable = !navigation.showUnavailable
                rebuild()
            }
            if (navigation.showUnavailable) for (party in groups.second) row(rows, party, rowWidth)
        }
        if (layout.wide) {
            left.arrangeElements()
            left
                .addChild(
                    ScrollableLayout(
                        Minecraft.getInstance(),
                        rows,
                        (layout.contentHeight - left.height - 7).coerceAtLeast(40),
                    )
                )
                .setMinWidth(rowWidth)
        } else left.addChild(rows)
    }

    private fun comparisons(
        page: LinearLayout,
        party: FinderParty,
        width: Int,
        role: DungeonRole?,
    ) {
        page.label(finderText("browse.requirements"), width, Palette.MUTED)

        if (role == null) {
            page.label(finderText("browse.choose_role"), width, Palette.MUTED)
            return
        }
        val requirements = party.rules.forRole(role)
        if (requirements.isEmpty())
            page.label(finderText("browse.no_rules"), width, Palette.SUCCESS)
        for ((metric, threshold) in requirements) {
            val value = finder.state?.stats?.value(metric, role, party.floor)
            val meets =
                value != null &&
                    value > 0 &&
                    if (metric.minimum) value >= threshold else value <= threshold
            page.label(
                finderText(
                    "browse.compare",
                    if (meets) "✓" else "×",
                    finderText("metric.${metric.key}"),
                    (if (metric.minimum) "≥" else "≤") + metricText(metric, threshold.toDouble()),
                    metricText(metric, value),
                ),
                width,
                if (meets) Palette.SUCCESS else Palette.DANGER,
            )
        }
    }

    private fun roster(page: LinearLayout, party: FinderParty, width: Int, role: DungeonRole?) {
        page.label(finderText("browse.roster"), width, Palette.MUTED)
        for ((index, slot) in party.slots.withIndex()) {
            val member = party.members.firstOrNull { it.slot == index }
            if (slot.filled) {
                val memberRow = page.addChild(LinearLayout.horizontal().spacing(5))
                if (member != null) memberRow.addChild(FinderAvatarWidget(member.uuid))
                memberRow.label(
                    Component.literal(
                        "${slot.role.short}  ${member?.name ?: "…"}${if (member?.leader == true) " ★" else ""}"
                    ),
                    width - 17,
                )
                if (member != null)
                    page.label(
                        finderText(
                            "browse.member_stats",
                            metricText(
                                FinderMetric.CATACOMBS,
                                member.stats[FinderMetric.CATACOMBS],
                            ),
                            metricText(FinderMetric.CLASS, member.stats[FinderMetric.CLASS]),
                            metricText(FinderMetric.POWER, member.stats[FinderMetric.POWER]),
                        ),
                        width,
                        Palette.MUTED,
                    )
            } else
                page.button(
                    finderText("browse.open_slot", finderText("role.${slot.role.key}")),
                    width,
                    primary = role == slot.role,
                ) {
                    navigation.reserveRole = slot.role
                    rebuild()
                }
        }
    }
}
