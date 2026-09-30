package dev.mithril.mithrilpf.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.mithril.mithrilpf.finder.DungeonRole
import dev.mithril.mithrilpf.finder.FinderMetric
import dev.mithril.mithrilpf.finder.FinderParty
import dev.mithril.mithrilpf.finder.FinderPreset
import dev.mithril.mithrilpf.finder.FinderRules

class FinderDraft(val floor: String, party: FinderParty? = null) {
    val partyId = party?.id
    var leader =
        party?.members?.firstOrNull { it.leader }?.let { party.slots[it.slot].role }
            ?: DungeonRole.MAGE
    var duplicates = party?.slots?.map { it.role }?.distinct()?.size?.let { it < 5 } ?: false
    var slots = party?.slots?.map { it.role } ?: DungeonRole.entries.toList()
    var shared =
        party?.rules?.shared.orEmpty().mapValues { inputValue(it.key, it.value) }.toMutableMap()
    var perClass =
        party
            ?.rules
            ?.perClass
            .orEmpty()
            .mapValues { (_, rules) ->
                rules.mapValues { inputValue(it.key, it.value) }.toMutableMap()
            }
            .toMutableMap()
    var exempt = party?.rules?.exempt.orEmpty()
    var blocked = party?.blocked.orEmpty()
    var names = ""
    var editingRole: DungeonRole = DungeonRole.ARCHER
    var recordsExpanded = false
    var classesExpanded = false
    var blocksExpanded = false
    var error: FinderMetric? = null
    var invalidNames = false

    fun restore(preset: FinderPreset) {
        leader = preset.leader
        duplicates = preset.duplicates
        slots = preset.slots
        shared = preset.rules.shared.mapValues { inputValue(it.key, it.value) }.toMutableMap()
        perClass =
            preset.rules.perClass
                .mapValues { (_, rules) ->
                    rules.mapValues { inputValue(it.key, it.value) }.toMutableMap()
                }
                .toMutableMap()
        exempt = preset.rules.exempt
    }

    fun preset() =
        FinderPreset(
            rules(),
            leader,
            duplicates,
            if (duplicates) slots else DungeonRole.entries.toList(),
        )

    fun rules(): FinderRules {
        error = null
        fun parse(values: Map<FinderMetric, String>) = buildMap {
            for ((metric, raw) in values) try {
                metricInput(metric, raw)?.let { put(metric, it) }
            } catch (failure: IllegalArgumentException) {
                error = metric
                throw failure
            }
        }
        return FinderRules(parse(shared), perClass.mapValues { parse(it.value) }, exempt)
    }

    fun body(editing: Boolean): JsonObject {
        val rules = rules()
        val names = names.split(Regex("[\\s,]+")).filter { it.isNotBlank() }.distinct()
        invalidNames = names.size > 100 || names.any { !it.matches(Regex("[A-Za-z0-9_]{1,16}")) }
        require(!invalidNames)
        return JsonObject().apply {
            add("rules", rules.json())
            add("block_names", JsonArray().apply { names.forEach(::add) })
            if (editing) add("blocked", JsonArray().apply { blocked.keys.forEach(::add) })
            else {
                addProperty("version", 1)
                addProperty("floor", floor)
                addProperty("leader_class", leader.key)
                addProperty("allow_duplicates", duplicates)
                add(
                    "roles",
                    JsonArray().apply {
                        (if (duplicates) slots else DungeonRole.entries).forEach { add(it.key) }
                    },
                )
            }
        }
    }
}

internal fun inputValue(metric: FinderMetric, value: Int): String =
    when {
        metric.minimum -> value.toString()
        metric == FinderMetric.SS ->
            value.toBigDecimal().movePointLeft(3).stripTrailingZeros().toPlainString()
        else ->
            metricText(metric, value.toDouble()) +
                if (value % 1000 == 0) ""
                else "." + (value % 1000).toString().padStart(3, '0').trimEnd('0')
    }

internal fun steppedRequirement(metric: FinderMetric, raw: String, direction: Int): String {
    val current = runCatching {
        metricInput(metric, raw)
    }
        .getOrElse {
            return raw
        }
    val step =
        when (metric) {
            FinderMetric.POWER -> 50
            FinderMetric.S_PLUS,
            FinderMetric.SOLO,
            FinderMetric.TERMINALS -> 5000
            FinderMetric.SS -> 100
            else -> 1
        }
    val next = ((current ?: 0) + direction * step).coerceIn(0, metric.maximum)
    return if (next == 0) "" else inputValue(metric, next)
}
