package dev.mithril.mithrilpf.dungeontimer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class HudPosition(val x: Double = 0.02, val y: Double = 0.2, val scale: Double = 1.0)

data class TrackingSettings(
    val enabled: Boolean = true,
    val rooms: Boolean = true,
    val solo: Boolean = true,
    val table: Boolean = true,
    val split: Boolean = false,
    val ticks: Boolean = false,
    val dungeonOnly: Boolean = true,
    val paul: Boolean = false,
    val tablePosition: HudPosition = HudPosition(),
    val splitPosition: HudPosition = HudPosition(0.5, 0.2),
    val tickPosition: HudPosition = HudPosition(0.5, 0.3),
)

/** Worker-owned; unknown fields survive edits and malformed/newer files are never overwritten. */
class TrackingSettingsStore(private val path: Path) {
    fun load(): TrackingSettings = decode(read())

    private fun read(): JsonObject {
        if (!Files.exists(path)) return JsonObject().apply { addProperty("version", 1) }
        val bytes = Files.newInputStream(path).use { it.readNBytes(65537) }
        require(bytes.size <= 65536)
        return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.also {
            require(it["version"]?.toString() == "1") { "Unsupported tracking configuration" }
            decode(it)
        }
    }

    private fun decode(root: JsonObject): TrackingSettings {
        fun bool(key: String, default: Boolean): Boolean {
            val value = root[key] ?: return default
            require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
            return value.asBoolean
        }
        fun pos(key: String, fallback: HudPosition): HudPosition {
            val obj = root[key]?.asJsonObject ?: return fallback
            fun number(name: String, default: Double, min: Double, max: Double): Double {
                val value = obj[name] ?: return default
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
                return value.asDouble.also { require(it.isFinite() && it in min..max) }
            }
            return HudPosition(
                number("x", fallback.x, 0.0, 1.0),
                number("y", fallback.y, 0.0, 1.0),
                number("scale", fallback.scale, 0.5, 10.0),
            )
        }
        val d = TrackingSettings()
        return TrackingSettings(
            bool("enabled", d.enabled),
            bool("rooms", d.rooms),
            bool("solo", d.solo),
            bool("table", d.table),
            bool("split", d.split),
            bool("ticks", d.ticks),
            bool("dungeonOnly", d.dungeonOnly),
            bool("paul", d.paul),
            pos("tablePosition", d.tablePosition),
            pos("splitPosition", d.splitPosition),
            pos("tickPosition", d.tickPosition),
        )
    }

    fun save(settings: TrackingSettings) {
        val root = read()
        mapOf(
                "enabled" to settings.enabled,
                "rooms" to settings.rooms,
                "solo" to settings.solo,
                "table" to settings.table,
                "split" to settings.split,
                "ticks" to settings.ticks,
                "dungeonOnly" to settings.dungeonOnly,
                "paul" to settings.paul,
            )
            .forEach { (key, value) -> root.addProperty(key, value) }
        mapOf(
                "tablePosition" to settings.tablePosition,
                "splitPosition" to settings.splitPosition,
                "tickPosition" to settings.tickPosition,
            )
            .forEach { (key, value) ->
                val obj = root[key]?.asJsonObject ?: JsonObject()
                obj.addProperty("x", value.x)
                obj.addProperty("y", value.y)
                obj.addProperty("scale", value.scale)
                root.add(key, obj)
            }
        decode(root)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65536)
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "tracking-", ".tmp")
        try {
            Files.write(temp, bytes)
            try {
                Files.move(
                    temp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
