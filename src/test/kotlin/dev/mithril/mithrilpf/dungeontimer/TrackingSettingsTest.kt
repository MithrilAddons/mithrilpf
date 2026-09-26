package dev.mithril.mithrilpf.dungeontimer

import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class TrackingSettingsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory
        get() = temporary.root.toPath()

    @Test
    fun `defaults and all toggles and positions round trip`() {
        val file = directory.resolve("tracking.json")
        val store = TrackingSettingsStore(file)
        assertEquals(TrackingSettings(), store.load())
        assertFalse(Files.exists(file))
        val value =
            TrackingSettings(
                false,
                false,
                false,
                false,
                true,
                true,
                false,
                true,
                HudPosition(0.0, 1.0, 10.0),
                HudPosition(1.0, 0.0, 0.5),
                HudPosition(0.3, 0.7, 3.0),
            )
        store.save(value)
        assertEquals(value, TrackingSettingsStore(file).load())
    }

    @Test
    fun `unknown settings and nested position values survive editing`() {
        val file = directory.resolve("tracking.json")
        Files.writeString(
            file,
            """{"version":1,"future":{"value":42},"tablePosition":{"future":true}}""",
        )
        TrackingSettingsStore(file).save(TrackingSettings(ticks = true))
        val root = JsonParser.parseString(Files.readString(file)).asJsonObject
        assertEquals(42, root["future"].asJsonObject["value"].asInt)
        assertTrue(root["tablePosition"].asJsonObject["future"].asBoolean)
    }

    @Test
    fun `malformed oversized newer and invalid type configs are never overwritten`() {
        val file = directory.resolve("tracking.json")
        for (json in
            listOf(
                "{",
                "[]",
                "null",
                "{}",
                """{"version":2}""",
                """{"version":1,"ticks":"true"}""",
                """{"version":1,"tablePosition":{"scale":0.49}}""",
                """{"version":1,"tablePosition":{"x":1.01}}""",
                """{"version":1,"tablePosition":{"scale":1e999}}""",
                " ".repeat(65537),
            )) {
            Files.writeString(file, json)
            val store = TrackingSettingsStore(file)
            assertFails { store.load() }
            assertFails { store.save(TrackingSettings()) }
            assertEquals(json, Files.readString(file))
        }
    }

    @Test
    fun `invalid in memory coordinates cannot replace a valid config`() {
        val file = directory.resolve("tracking.json")
        val store = TrackingSettingsStore(file)
        store.save(TrackingSettings())
        val before = Files.readString(file)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01)) {
            assertFails { store.save(TrackingSettings(tablePosition = HudPosition(x = value))) }
            assertEquals(before, Files.readString(file))
        }
    }
}
