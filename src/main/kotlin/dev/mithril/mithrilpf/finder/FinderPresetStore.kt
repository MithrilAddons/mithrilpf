package dev.mithril.mithrilpf.finder

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class FinderPreset(
    val rules: FinderRules,
    val leader: DungeonRole,
    val duplicates: Boolean,
    val slots: List<DungeonRole>,
)

/** Per-account, per-floor preferences. Read and write only from the finder's worker. */
class FinderPresetStore(private val path: Path) {
    fun load(uuid: String): Map<String, FinderPreset> {
        val account = read().getAsJsonObject("accounts")?.getAsJsonObject(uuid) ?: return emptyMap()
        return listOf("F7", "M7")
            .mapNotNull { floor ->
                account.getAsJsonObject(floor)?.let { floor to decode(it) }
            }
            .toMap()
    }

    fun save(uuid: String, floor: String, preset: FinderPreset) {
        require(uuid.matches(Regex("[0-9a-f]{32}")) && floor in listOf("F7", "M7"))
        val root = read()
        val accounts =
            root.getAsJsonObject("accounts") ?: JsonObject().also { root.add("accounts", it) }
        val account = accounts.getAsJsonObject(uuid) ?: JsonObject().also { accounts.add(uuid, it) }
        val row = account.getAsJsonObject(floor) ?: JsonObject().also { account.add(floor, it) }
        // Update known fields while retaining unknown fields at every rules level.
        val rules = row.getAsJsonObject("rules") ?: JsonObject().also { row.add("rules", it) }
        fun values(target: JsonObject, source: Map<FinderMetric, Int>) {
            for (metric in FinderMetric.entries) target.remove(metric.key)
            for ((metric, value) in source) target.addProperty(metric.key, value)
        }
        val shared =
            rules.getAsJsonObject("shared") ?: JsonObject().also { rules.add("shared", it) }
        values(shared, preset.rules.shared)
        val perClass =
            rules.getAsJsonObject("per_class") ?: JsonObject().also { rules.add("per_class", it) }
        for (role in DungeonRole.entries) {
            val thresholds =
                perClass.getAsJsonObject(role.key)
                    ?: JsonObject().also { perClass.add(role.key, it) }
            values(thresholds, preset.rules.perClass[role].orEmpty())
        }
        rules.add("exempt", JsonArray().apply { preset.rules.exempt.forEach { add(it.key) } })
        row.addProperty("leader", preset.leader.key)
        row.addProperty("duplicates", preset.duplicates)
        row.add("slots", JsonArray().apply { preset.slots.forEach { add(it.key) } })
        decode(row)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65536)
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "finder-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun read(): JsonObject {
        if (!Files.exists(path)) return JsonObject().apply { addProperty("version", 1) }
        val bytes = Files.newInputStream(path).use { it.readNBytes(65537) }
        require(bytes.size <= 65536)
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(root.get("version")?.toString() == "1")
        root.getAsJsonObject("accounts")?.entrySet()?.forEach { (uuid, account) ->
            require(uuid.matches(Regex("[0-9a-f]{32}")))
            for (floor in listOf("F7", "M7")) account.asJsonObject
                .getAsJsonObject(floor)
                ?.let(::decode)
        }
        return root
    }

    private fun decode(row: JsonObject): FinderPreset {
        val rules = row.getAsJsonObject("rules")
        fun thresholds(values: JsonObject) = buildMap {
            for (metric in FinderMetric.entries) values.get(metric.key)?.let { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
                require(value.toString().matches(Regex("[0-9]+")))
                put(metric, value.asInt.also { require(it in 1..metric.maximum) })
            }
        }
        val known =
            FinderRules(
                thresholds(rules.getAsJsonObject("shared")),
                DungeonRole.entries
                    .mapNotNull { role ->
                        rules.getAsJsonObject("per_class").getAsJsonObject(role.key)?.let {
                            role to thresholds(it)
                        }
                    }
                    .toMap(),
                rules.getAsJsonArray("exempt").map { DungeonRole.from(it.asString) }.toSet(),
            )
        val leader = DungeonRole.from(row.get("leader").asString)
        require(row.get("duplicates").asJsonPrimitive.isBoolean)
        val duplicates = row.get("duplicates").asBoolean
        val slots = row.getAsJsonArray("slots").map { DungeonRole.from(it.asString) }
        require(slots.size == 5 && leader in slots && (duplicates || slots.distinct().size == 5))
        return FinderPreset(known, leader, duplicates, slots)
    }
}
