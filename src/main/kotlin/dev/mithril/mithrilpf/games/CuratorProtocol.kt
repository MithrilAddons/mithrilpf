package dev.mithril.mithrilpf.games

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class Match {
    EXACT,
    PARTIAL,
    NONE,
}

enum class Arrow {
    UP,
    DOWN,
}

/** How one clue column of a guess compares with the answer; the arrow points at the answer. */
data class Feedback(val match: Match, val arrow: Arrow? = null)

enum class Column(val key: String) {
    RARITY("rarity"),
    TYPE("type"),
    MUSEUM("museum"),
    STAGE("stage"),
    REQUIREMENTS("requirements"),
    SOULBOUND("soulbound"),
    ORIGIN("origin"),
    MARKET("market"),
    NPC("npc"),
    LENGTH("length"),
}

/** One item's clue values as the backend reports them. */
data class ClueValues(
    val rarity: String?,
    val type: String?,
    val museum: String?,
    val stage: String?,
    val requirements: Map<String, Int?>,
    val soulbound: String?,
    val origin: String?,
    val market: Double?,
    val npc: Double?,
    val length: Int,
)

data class CuratorGuess(
    val item: String,
    val name: String,
    val values: ClueValues,
    val feedback: Map<Column, Feedback>,
    val family: Boolean,
)

data class ItemIcon(val material: String?, val durability: Int?, val skin: String?)

data class CuratorAnswer(
    val item: String,
    val name: String,
    val values: ClueValues,
    val icon: ItemIcon,
)

data class PlayerStats(val played: Int, val solved: Int, val streak: Int, val bestStreak: Int)

enum class RoundState {
    PREPARING,
    PLAYING,
    SOLVED,
    FAILED,
}

data class CuratorDay(
    val day: String,
    val number: Int?,
    val state: RoundState,
    val limit: Int,
    val resetsAt: Long,
    val guesses: List<CuratorGuess>,
    val answer: CuratorAnswer?,
    val stats: PlayerStats?,
) {
    val finished
        get() = state == RoundState.SOLVED || state == RoundState.FAILED
}

data class StandingRow(
    val rank: Int,
    val name: String,
    val points: Int,
    val solved: Int,
    val played: Int,
    val streak: Int,
    val you: Boolean,
)

data class SeasonStats(
    val points: Int,
    val rank: Int?,
    val played: Int,
    val solved: Int,
    val average: Double?,
    val histogram: List<Int>,
    val failed: Int,
    val streak: Int,
    val bestStreak: Int,
)

data class Leaderboard(
    val season: String,
    val day: Int,
    val days: Int,
    val players: Int,
    val top: List<StandingRow>,
    val you: StandingRow?,
    val stats: SeasonStats,
)

data class CatalogItem(val id: String, val name: String)

data class Catalog(val version: String, val items: List<CatalogItem>)

/** Parses Curator responses strictly; anything unexpected fails the whole response. */
object CuratorProtocol {
    private val DAY = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val ITEM = Regex("[A-Za-z0-9_:;.\\-]{1,128}")

    fun day(json: String): CuratorDay {
        val root = parse(json)
        val state = RoundState.valueOf(root.get("state").asString.uppercase())
        val day = root.get("day").asString
        require(day.matches(DAY))
        val resetsAt = root.get("resets_at").asLong
        if (state == RoundState.PREPARING)
            return CuratorDay(day, null, state, 0, resetsAt, emptyList(), null, null)
        val guesses = root.getAsJsonArray("guesses").map { guess(it.asJsonObject) }
        val limit = root.get("limit").asInt
        require(limit in 1..20 && guesses.size <= limit)
        return CuratorDay(
            day,
            root.get("number").asInt.also { require(it >= 1) },
            state,
            limit,
            resetsAt,
            guesses,
            root.getAsJsonObject("answer")?.let(::answer),
            root.getAsJsonObject("stats")?.let(::playerStats),
        )
    }

    fun leaderboard(json: String): Leaderboard {
        val root = parse(json)
        val stats = root.getAsJsonObject("stats")
        val histogram = stats.getAsJsonArray("histogram").map { it.asInt }
        require(histogram.size in 1..20)
        val top = root.getAsJsonArray("top").map { standing(it.asJsonObject) }
        require(top.size <= 10)
        return Leaderboard(
            root.get("season").asString.also { require(it.matches(Regex("\\d{4}-\\d{2}"))) },
            root.get("day").asInt,
            root.get("days").asInt,
            root.get("players").asInt,
            top,
            optional(root, "you")?.let { standing(it.asJsonObject) },
            SeasonStats(
                stats.get("points").asInt,
                optional(stats, "rank")?.asInt,
                stats.get("played").asInt,
                stats.get("solved").asInt,
                optional(stats, "average")?.asDouble,
                histogram,
                stats.get("failed").asInt,
                stats.get("streak").asInt,
                stats.get("best_streak").asInt,
            ),
        )
    }

    /** A response with `unchanged` keeps the cached list. */
    fun catalog(json: String, cached: Catalog?): Catalog {
        val root = parse(json)
        val version = root.get("catalog").asString
        require(version.length in 1..64)
        if (optional(root, "unchanged")?.asBoolean == true) {
            requireNotNull(cached)
            require(cached.version == version)
            return cached
        }
        val items = root.getAsJsonArray("items")
        require(items.size() <= 20000)
        return Catalog(
            version,
            items.map {
                val pair = it.asJsonArray
                require(pair.size() == 2)
                CatalogItem(
                    pair[0].asString.also { id -> require(id.matches(ITEM)) },
                    pair[1].asString.also { name -> require(name.length in 1..64) },
                )
            },
        )
    }

    fun guessBody(day: String, item: String) =
        JsonObject().apply {
            addProperty("version", 1)
            addProperty("day", day)
            addProperty("item", item)
        }

    private fun parse(json: String): JsonObject {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("version").asInt == 1)
        return root
    }

    private fun optional(root: JsonObject, key: String): JsonElement? =
        root.get(key)?.takeUnless { it.isJsonNull }

    private fun text(root: JsonObject, key: String) = optional(root, key)?.asString

    private fun guess(root: JsonObject): CuratorGuess {
        val feedback = root.getAsJsonObject("feedback")
        return CuratorGuess(
            root.get("item").asString.also { require(it.matches(ITEM)) },
            root.get("name").asString,
            values(root.getAsJsonObject("values")),
            Column.entries.associateWith { column ->
                val entry = feedback.getAsJsonObject(column.key)
                Feedback(
                    Match.valueOf(entry.get("match").asString.uppercase()),
                    text(entry, "arrow")?.let { Arrow.valueOf(it.uppercase()) },
                )
            },
            root.get("family").asBoolean,
        )
    }

    private fun values(root: JsonObject) =
        ClueValues(
            text(root, "rarity"),
            text(root, "type"),
            text(root, "museum"),
            text(root, "stage"),
            root.getAsJsonObject("requirements").entrySet().associate { (key, level) ->
                key to level.takeUnless { it.isJsonNull }?.asInt
            },
            text(root, "soulbound"),
            text(root, "origin"),
            optional(root, "market")?.asDouble,
            optional(root, "npc")?.asDouble,
            root.get("length").asInt,
        )

    private fun answer(root: JsonObject): CuratorAnswer {
        val icon = root.getAsJsonObject("icon")
        return CuratorAnswer(
            root.get("item").asString.also { require(it.matches(ITEM)) },
            root.get("name").asString,
            values(root.getAsJsonObject("values")),
            ItemIcon(
                text(icon, "material"),
                optional(icon, "durability")?.asInt,
                text(icon, "skin")?.takeIf { it.length <= 4096 },
            ),
        )
    }

    private fun playerStats(root: JsonObject) =
        PlayerStats(
            root.get("played").asInt,
            root.get("solved").asInt,
            root.get("streak").asInt,
            root.get("best_streak").asInt,
        )

    private fun standing(root: JsonObject) =
        StandingRow(
            root.get("rank").asInt,
            root.get("name").asString.take(16),
            root.get("points").asInt,
            root.get("solved").asInt,
            root.get("played").asInt,
            root.get("streak").asInt,
            root.get("you").asBoolean,
        )
}
