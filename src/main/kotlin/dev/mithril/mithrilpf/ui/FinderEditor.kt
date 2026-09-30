package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.finder.DungeonRole
import dev.mithril.mithrilpf.finder.FinderClient
import dev.mithril.mithrilpf.finder.FinderMetric
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.network.chat.Component

class FinderEditor(
    private val finder: FinderClient,
    private val layout: FinderLayout,
    private val draft: FinderDraft,
    private val editing: Boolean,
    private val rebuild: () -> Unit,
    private val cancel: () -> Unit,
    private val saved: () -> Unit,
    private val changeFloor: (String) -> Unit,
) {
    fun build(): LinearLayout =
        LinearLayout.vertical().spacing(10).apply {
            val heading = addChild(LinearLayout.horizontal().spacing(6))
            heading.button(finderText("editor.parties"), 75, action = cancel)
            heading.label(
                finderText(if (editing) "editor.edit" else "editor.create", draft.floor),
                (layout.pageWidth - 160).coerceAtLeast(70),
            )
            heading.button(finderText("editor.reset_short"), 65) {
                draft.shared.clear()
                draft.perClass.clear()
                draft.exempt = emptySet()
                draft.error = null
                rebuild()
            }
            val columns =
                addChild(
                    if (layout.wide) LinearLayout.horizontal().spacing(12)
                    else LinearLayout.vertical().spacing(12)
                )
            val width = if (layout.wide) (layout.pageWidth * 0.65).toInt() else layout.pageWidth
            val form = columns.addChild(LinearLayout.vertical().spacing(9))
            identity(form, width)
            form.label(finderText("editor.requirements"), width, Palette.MUTED)
            val main = listOf(FinderMetric.CATACOMBS, FinderMetric.S_PLUS, FinderMetric.POWER)
            fields(form, main, draft.shared, width, columns = width >= 330)
            records(form, width, main)
            classRules(form, width)
            blockedPlayers(form, width)
            publishControls(form, width)
            val previewWidth = if (layout.wide) layout.pageWidth - width - 12 else width
            columns.addChild(
                FinderListingPreview(
                    previewWidth,
                    draft,
                    finder.state?.name.orEmpty(),
                    finder.state?.stats,
                )
            )
        }

    private fun identity(form: LinearLayout, width: Int) {
        val identity =
            form.addChild(
                if (width >= 330) LinearLayout.horizontal().spacing(10)
                else LinearLayout.vertical().spacing(6)
            )
        val floor = identity.addChild(LinearLayout.vertical().spacing(4))
        floor.label(finderText("editor.floor"), 62, Palette.MUTED)
        val floors = floor.addChild(LinearLayout.horizontal().spacing(2))
        for (value in listOf("M7", "F7")) floors.button(
            Component.literal(value),
            30,
            draft.floor == value,
            enabled = !editing,
        ) {
            changeFloor(value)
        }
        val roles = identity.addChild(LinearLayout.vertical().spacing(4))
        roles.label(finderText("editor.your_class"), width - 72, Palette.MUTED)
        val choices = roles.addChild(LinearLayout.horizontal().spacing(3))
        val roleWidth = ((if (width >= 330) width - 72 else width) - 12) / 5
        for (role in DungeonRole.entries) choices
            .addChild(
                FinderRoleButton(roleWidth, role, draft.leader == role) {
                    draft.leader = role
                    rebuild()
                }
            )
            .active = !editing
        duplicateSlots(form, width)
    }

    private fun fields(
        page: LinearLayout,
        metrics: List<FinderMetric>,
        values: MutableMap<FinderMetric, String>,
        width: Int,
        columns: Boolean = false,
    ) {
        val row = if (columns) page.addChild(LinearLayout.horizontal().spacing(6)) else page
        val fieldWidth = if (columns) (width - 12) / 3 else width
        for (metric in metrics) {
            val field = row.addChild(LinearLayout.vertical().spacing(4))
            field.label(
                finderText(
                    "editor.rule",
                    finderText(
                        if (
                            metric in
                                listOf(
                                    FinderMetric.CATACOMBS,
                                    FinderMetric.S_PLUS,
                                    FinderMetric.POWER,
                                )
                        )
                            "short_metric.${metric.key}"
                        else "metric.${metric.key}"
                    ),
                    if (metric.minimum) "≥" else "≤",
                ),
                fieldWidth,
                Palette.MUTED,
            )
            val controls = field.addChild(LinearLayout.horizontal().spacing(2))
            lateinit var input: EditBox
            controls.button(Component.literal("−"), 18) {
                input.value = steppedRequirement(metric, input.value, -1)
            }
            input =
                controls
                    .addChild(
                        EditBox(
                            Minecraft.getInstance().font,
                            fieldWidth - 40,
                            20,
                            finderText("metric.${metric.key}"),
                        )
                    )
                    .apply {
                        value = values[metric].orEmpty()
                        setMaxLength(16)
                        setHint(
                            finderText(
                                if (
                                    metric in
                                        listOf(
                                            FinderMetric.S_PLUS,
                                            FinderMetric.SOLO,
                                            FinderMetric.TERMINALS,
                                        )
                                )
                                    "editor.time_hint"
                                else "editor.any"
                            )
                        )
                        setResponder { values[metric] = it }
                    }
            controls.button(Component.literal("+"), 18) {
                input.value = steppedRequirement(metric, input.value, 1)
            }
        }
    }

    private fun summary(page: LinearLayout, values: Map<FinderMetric, String>, width: Int) {
        val text =
            values
                .filterValues { it.isNotBlank() }
                .entries
                .joinToString(" · ") { (metric, value) ->
                    finderText("metric.${metric.key}").string + " " + value
                }
        if (text.isNotEmpty()) page.label(Component.literal(text), width, Palette.MUTED)
    }

    private fun records(form: LinearLayout, width: Int, main: List<FinderMetric>) {
        form.button(
            finderText(
                if (draft.recordsExpanded) "editor.records_close" else "editor.records_open"
            ),
            width,
        ) {
            draft.recordsExpanded = !draft.recordsExpanded
            rebuild()
        }
        val extra = FinderMetric.entries.filter { it !in main }
        if (draft.recordsExpanded) fields(form, extra, draft.shared, width)
        else summary(form, draft.shared.filterKeys { it in extra }, width)
    }

    private fun classRules(form: LinearLayout, width: Int) {
        val classCount =
            draft.perClass.values.sumOf { values -> values.count { it.value.isNotBlank() } } +
                draft.exempt.size
        form.button(
            finderText(
                if (draft.classesExpanded) "editor.classes_close" else "editor.classes_open",
                classCount,
            ),
            width,
        ) {
            draft.classesExpanded = !draft.classesExpanded
            rebuild()
        }
        if (draft.classesExpanded) {
            val classes = form.addChild(LinearLayout.horizontal().spacing(3))
            for (role in DungeonRole.entries) classes.addChild(
                FinderRoleButton((width - 12) / 5, role, draft.editingRole == role) {
                    draft.editingRole = role
                    rebuild()
                }
            )
            val role = draft.editingRole
            form.label(
                finderText("editor.class_rules", finderText("role.${role.key}")),
                width,
                Palette.MUTED,
            )
            fields(
                form,
                FinderMetric.entries.toList(),
                draft.perClass.getOrPut(role) { mutableMapOf() },
                width,
            )
            form.button(
                finderText(
                    "editor.class_exempt",
                    finderText(if (role in draft.exempt) "on" else "off"),
                ),
                width,
            ) {
                draft.exempt =
                    if (role in draft.exempt) draft.exempt - role else draft.exempt + role
                rebuild()
            }
        }
    }

    private fun blockedPlayers(form: LinearLayout, width: Int) {
        form.button(
            finderText(
                if (draft.blocksExpanded) "editor.blocks_close" else "editor.blocks_open",
                draft.blocked.size + draft.names.split(Regex("[\\s,]+")).count { it.isNotBlank() },
            ),
            width,
        ) {
            draft.blocksExpanded = !draft.blocksExpanded
            rebuild()
        }
        if (draft.blocksExpanded) {
            form.input(finderText("editor.block"), width, draft.names, 1700) {
                draft.names = it
            }
            for ((uuid, name) in draft.blocked) form.button(
                finderText("editor.unblock", name),
                width,
            ) {
                draft.blocked = draft.blocked - uuid
                rebuild()
            }
        }
    }

    private fun publishControls(form: LinearLayout, width: Int) {
        draft.error?.let {
            form.label(
                finderText("editor.invalid", finderText("metric.${it.key}")),
                width,
                Palette.DANGER,
            )
        }
        if (draft.invalidNames)
            form.label(finderText("editor.invalid_names"), width, Palette.DANGER)
        val rolesValid = !draft.duplicates || draft.leader in draft.slots
        if (!rolesValid) form.label(finderText("editor.missing_leader"), width, Palette.DANGER)
        if (layout.wide) {
            form.arrangeElements()
            form.addChild(
                SpacerElement.height((layout.contentHeight - form.height - 80).coerceAtLeast(0))
            )
        }
        form.button(
            finderText(if (editing) "editor.save" else "editor.publish"),
            width.coerceAtMost(160),
            primary = true,
            enabled = !finder.busy && rolesValid,
        ) {
            val body = runCatching { draft.body(editing) }.getOrNull()
            if (body == null) rebuild()
            else
                finder.action(
                    if (editing) "edit" else "publish",
                    body,
                    remember = draft.preset(),
                ) { success ->
                    if (success) saved() else rebuild()
                }
        }
        form.label(finderText("editor.edit_later"), width, Palette.MUTED)
    }

    private fun duplicateSlots(form: LinearLayout, width: Int) {
        if (!editing) {
            form.button(
                finderText("editor.duplicates", finderText(if (draft.duplicates) "on" else "off")),
                width,
            ) {
                draft.duplicates = !draft.duplicates
                rebuild()
            }
            if (draft.duplicates) {
                form.label(finderText("editor.slot_roles"), width, Palette.MUTED)
                val slots = form.addChild(LinearLayout.horizontal().spacing(3))
                for (index in 0..4) slots.addChild(
                    FinderRoleButton((width - 12) / 5, draft.slots[index], false) {
                        draft.slots =
                            draft.slots.mapIndexed { i, value ->
                                if (i == index) DungeonRole.entries[(value.ordinal + 1) % 5]
                                else value
                            }
                        rebuild()
                    }
                )
            }
        }
    }
}
