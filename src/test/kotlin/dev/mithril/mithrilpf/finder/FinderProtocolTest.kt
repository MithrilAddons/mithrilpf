package dev.mithril.mithrilpf.finder

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinderProtocolTest {
    private fun fixture() =
        javaClass.getResourceAsStream("/contracts/party-v1.json")!!.bufferedReader().use {
            JsonParser.parseString(it.readText()).asJsonObject
        }

    @Test
    fun readsTheSharedWebsiteProtocolIncludingMemberAndOwnStats() {
        val root = fixture()
        val json = root.getAsJsonObject("state")
        val uuid = json.getAsJsonObject("you").get("uuid").asString
        val state = FinderProtocol.state(json.toString(), uuid)!!
        val party = assertNotNull(state.party)
        val stats = assertNotNull(state.stats)
        assertEquals("Sable", state.name)
        assertEquals(2, party.members.size)
        assertEquals(3, party.slots.count { !it.filled })
        assertEquals(51.0, stats.value(FinderMetric.CATACOMBS, DungeonRole.HEALER, "M7"))
        assertEquals(49.5, stats.value(FinderMetric.CLASS, DungeonRole.HEALER, "M7"))
        assertEquals(331000.0, stats.value(FinderMetric.S_PLUS, DungeonRole.HEALER, "M7"))
        assertNull(stats.value(FinderMetric.SOLO, DungeonRole.HEALER, "M7"))
        assertTrue(stats.qualifies(party.rules, DungeonRole.HEALER, "M7"))
        assertEquals(party.rules, FinderProtocol.rules(party.rules.json()))
        assertEquals("reserved", state.notices.single().kind)
        assertFalse(party.youLead)
    }

    @Test
    fun classOverridesStayStricterAndCataExemptionOnlyRemovesTheSharedRule() {
        val rules =
            FinderRules(
                mapOf(FinderMetric.CATACOMBS to 50, FinderMetric.S_PLUS to 300000),
                mapOf(
                    DungeonRole.HEALER to
                        mapOf(FinderMetric.CATACOMBS to 40, FinderMetric.S_PLUS to 350000),
                    DungeonRole.TANK to
                        mapOf(FinderMetric.CATACOMBS to 52, FinderMetric.S_PLUS to 250000),
                ),
                setOf(DungeonRole.HEALER),
            )
        assertEquals(
            mapOf(FinderMetric.CATACOMBS to 40, FinderMetric.S_PLUS to 300000),
            rules.forRole(DungeonRole.HEALER),
        )
        assertEquals(
            mapOf(FinderMetric.CATACOMBS to 52, FinderMetric.S_PLUS to 250000),
            rules.forRole(DungeonRole.TANK),
        )
        val unknown = FinderStats(emptyMap(), emptyMap(), emptyMap())
        assertFalse(unknown.qualifies(rules, DungeonRole.ARCHER, "M7"))
        assertTrue(unknown.qualifies(FinderRules(), DungeonRole.ARCHER, "M7"))
    }

    @Test
    fun rejectsMismatchedAccountsAndUnknownProtocolWithoutReplacingAView() {
        val json = fixture().getAsJsonObject("state")
        assertFails { FinderProtocol.state(json.toString(), "a".repeat(32)) }
        json.addProperty("version", 2)
        assertFails { FinderProtocol.parse(json.toString()) }
        assertNull(FinderProtocol.state("""{"version":1,"unchanged":true}""", "a".repeat(32)))
    }
}
