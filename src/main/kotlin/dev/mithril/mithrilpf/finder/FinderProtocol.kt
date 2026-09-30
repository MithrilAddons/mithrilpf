package dev.mithril.mithrilpf.finder

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class DungeonRole(val key: String, val short: String) {
    ARCHER("archer", "A"),
    BERSERK("berserk", "B"),
    HEALER("healer", "H"),
    MAGE("mage", "M"),
    TANK("tank", "T");

    companion object {
        fun from(key: String) = entries.first { it.key == key }
    }
}

enum class FinderMetric(val key: String, val minimum: Boolean, val maximum: Int) {
    CATACOMBS("catacombs", true, 1000),
    S_PLUS("s_plus_ms", false, 7200000),
    POWER("magical_power", true, 10000),
    CLASS("class_level", true, 50),
    SOLO("solo_ms", false, 7200000),
    TERMINALS("terminals_ms", false, 7200000),
    SS("ss_ms", false, 20000);

    companion object {
        fun from(key: String) = entries.first { it.key == key }
    }
}

data class FinderRules(
    val shared: Map<FinderMetric, Int> = emptyMap(),
    val perClass: Map<DungeonRole, Map<FinderMetric, Int>> = emptyMap(),
    val exempt: Set<DungeonRole> = emptySet(),
) {
    fun forRole(role: DungeonRole): Map<FinderMetric, Int> = buildMap {
        putAll(shared.filterKeys { it != FinderMetric.CATACOMBS || role !in exempt })
        for ((metric, value) in perClass[role].orEmpty()) {
            val previous = get(metric)
            put(
                metric,
                if (previous == null) value
                else if (metric.minimum) maxOf(previous, value) else minOf(previous, value),
            )
        }
    }

    fun json() =
        JsonObject().apply {
            fun values(rules: Map<FinderMetric, Int>) =
                JsonObject().apply {
                    for ((metric, value) in rules) addProperty(metric.key, value)
                }
            add("shared", values(shared))
            add(
                "per_class",
                JsonObject().apply { for ((role, rules) in perClass) add(role.key, values(rules)) },
            )
            add("exempt", JsonArray().apply { for (role in exempt) add(role.key) })
        }
}

data class FinderStats(
    val shared: Map<FinderMetric, Double?>,
    val roles: Map<DungeonRole, Double?>,
    val floors: Map<String, Map<FinderMetric, Double?>>,
) {
    fun value(metric: FinderMetric, role: DungeonRole, floor: String): Double? =
        when (metric) {
            FinderMetric.CLASS -> roles[role]
            FinderMetric.S_PLUS,
            FinderMetric.SOLO,
            FinderMetric.TERMINALS -> floors[floor]?.get(metric)
            else -> shared[metric]
        }

    fun qualifies(rules: FinderRules, role: DungeonRole, floor: String) =
        rules.forRole(role).all { (metric, threshold) ->
            val actual = value(metric, role, floor)
            actual != null &&
                actual > 0 &&
                if (metric.minimum) actual >= threshold else actual <= threshold
        }
}

data class FinderSlot(val role: DungeonRole, val filled: Boolean)

data class FinderMember(
    val slot: Int,
    val uuid: String,
    val name: String,
    val leader: Boolean,
    val stats: Map<FinderMetric, Double?>,
)

data class FinderMessage(val id: String, val name: String?, val text: String)

data class FinderParty(
    val id: String,
    val floor: String,
    val leader: String,
    val slots: List<FinderSlot>,
    val rules: FinderRules,
    val averageCata: Double?,
    val averageTime: Double?,
    val members: List<FinderMember>,
    val youLead: Boolean,
    val paused: Boolean,
    val completed: Boolean,
    val invited: Boolean,
    val joined: List<Boolean>,
    val accepted: List<Boolean>,
    val deadline: Double?,
    val messages: List<FinderMessage>,
    val blocked: Map<String, String>,
    val leaderUuid: String? = null,
)

data class FinderLooking(val floor: String, val roles: Set<DungeonRole>, val maximumTime: Int?)

data class FinderNotice(val id: String, val kind: String, val party: String?, val reason: String?)

data class FinderState(
    val version: Long,
    val id: String,
    val serverTime: Double,
    val uuid: String,
    val name: String,
    val inGame: Boolean,
    val stats: FinderStats?,
    val looking: FinderLooking?,
    val party: FinderParty?,
    val notices: List<FinderNotice>,
)

/** Decode bounded responses on a worker, then publish immutable value snapshots to the client. */
object FinderProtocol {
    fun parse(value: String): JsonObject {
        require(value.length <= 1048576)
        return JsonParser.parseString(value).asJsonObject.also {
            require(it.get("version")?.toString() == "1")
        }
    }

    fun listings(value: String, floor: String): List<FinderParty> {
        val root = parse(value)
        require(root.string("floor") == floor)
        return root.array("parties", 800).map { party(it.asJsonObject) }
    }

    fun detail(value: String, id: String): FinderParty =
        party(parse(value)).also { require(it.id == id) }

    fun state(value: String, uuid: String): FinderState? {
        val root = parse(value)
        if (root.bool("unchanged")) return null
        val you = root.getAsJsonObject("you")
        require(you.string("uuid") == uuid)
        val looking =
            you.obj("looking")?.let {
                FinderLooking(
                    it.string("floor"),
                    it.array("classes", 5).map { role -> DungeonRole.from(role.asString) }.toSet(),
                    it.number("limit")?.toInt(),
                )
            }
        return FinderState(
            root.get("state_version").asLong,
            root.string("state_id"),
            root.get("server_time").asDouble,
            uuid,
            you.string("name"),
            you.bool("in_game"),
            you.obj("stats")?.let(::stats),
            looking,
            root.obj("party")?.let(::party),
            root.array("notices", 10).map { item ->
                val notice = item.asJsonObject
                FinderNotice(
                    notice.string("id"),
                    notice.string("kind"),
                    notice.optional("party"),
                    notice.optional("reason"),
                )
            },
        )
    }

    fun rules(root: JsonObject): FinderRules {
        fun thresholds(row: JsonObject) =
            row.entrySet().associate { (key, value) ->
                val metric = FinderMetric.from(key)
                val threshold = value.asInt
                require(threshold in 1..metric.maximum)
                metric to threshold
            }
        return FinderRules(
            thresholds(root.getAsJsonObject("shared")),
            root.getAsJsonObject("per_class").entrySet().associate { (role, values) ->
                DungeonRole.from(role) to thresholds(values.asJsonObject)
            },
            root.array("exempt", 5).map { DungeonRole.from(it.asString) }.toSet(),
        )
    }

    private fun stats(root: JsonObject): FinderStats =
        FinderStats(
            listOf(FinderMetric.CATACOMBS, FinderMetric.POWER, FinderMetric.SS).associateWith {
                root.number(it.key)
            },
            DungeonRole.entries.associateWith { root.obj("class_levels")?.number(it.key) },
            listOf("F7", "M7").associateWith { floor ->
                listOf(FinderMetric.S_PLUS, FinderMetric.SOLO, FinderMetric.TERMINALS)
                    .associateWith { root.obj(it.key)?.number(floor) }
            },
        )

    private fun party(root: JsonObject): FinderParty {
        val id = root.string("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{12}")))
        val floor = root.string("floor")
        require(floor in setOf("F7", "M7"))
        val team = root.getAsJsonObject("team")
        val slots =
            root.array("slots", 5).map { item ->
                item.asJsonObject.let {
                    FinderSlot(DungeonRole.from(it.string("role")), it.bool("filled"))
                }
            }
        require(slots.size == 5)
        return FinderParty(
            id,
            floor,
            root.string("leader"),
            slots,
            rules(root.getAsJsonObject("rules")),
            team.number("catacombs_avg"),
            team.number("s_plus_ms_avg"),
            root.array("members", 5).map { item ->
                item.asJsonObject.let { member ->
                    FinderMember(
                        member.get("slot").asInt,
                        member.string("uuid"),
                        member.string("name"),
                        member.bool("leader"),
                        FinderMetric.entries.associateWith { member.obj("stats")?.number(it.key) },
                    )
                }
            },
            root.bool("you_lead"),
            root.bool("paused"),
            root.bool("completed"),
            root.bool("invited"),
            root.array("joined", 5).map { it.asBoolean },
            root.array("accepted", 5).map { it.asBoolean },
            root.number("join_deadline"),
            root.array("messages", 100).map { item ->
                item.asJsonObject.let { message ->
                    FinderMessage(
                        message.string("id"),
                        message.obj("sender")?.string("name"),
                        message.string("text"),
                    )
                }
            },
            root.array("blocked", 100).associate { item ->
                item.asJsonObject.let { it.string("uuid") to it.string("name") }
            },
            root.optional("leader_uuid"),
        )
    }

    private fun JsonObject.string(key: String) =
        get(key).asString.also { require(it.length <= 1024 && !it.contains('§')) }

    private fun JsonObject.optional(key: String) =
        get(key)?.takeUnless(JsonElement::isJsonNull)?.asString

    private fun JsonObject.bool(key: String) =
        get(key)?.takeUnless(JsonElement::isJsonNull)?.asBoolean ?: false

    private fun JsonObject.obj(key: String) =
        get(key)?.takeUnless(JsonElement::isJsonNull)?.asJsonObject

    private fun JsonObject.number(key: String) =
        get(key)?.takeUnless(JsonElement::isJsonNull)?.asDouble?.also {
            require(it.isFinite() && it >= 0)
        }

    private fun JsonObject.array(key: String, maximum: Int) =
        (get(key)?.asJsonArray ?: JsonArray()).also { require(it.size() <= maximum) }
}
