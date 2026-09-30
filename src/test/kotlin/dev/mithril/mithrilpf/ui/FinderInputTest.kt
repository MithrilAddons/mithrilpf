package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.finder.DungeonRole
import dev.mithril.mithrilpf.finder.FinderMetric
import dev.mithril.mithrilpf.finder.FinderPreset
import dev.mithril.mithrilpf.finder.FinderRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class FinderInputTest {
    @Test
    fun requirementStepsPreserveInvalidInputAndRespectBounds() {
        assertEquals("", steppedRequirement(FinderMetric.CATACOMBS, "1", -1))
        assertEquals("50", steppedRequirement(FinderMetric.CLASS, "50", 1))
        assertEquals("1350", steppedRequirement(FinderMetric.POWER, "1300", 1))
        assertEquals("5:26.025", steppedRequirement(FinderMetric.S_PLUS, "5:31.025", -1))
        assertEquals("bad", steppedRequirement(FinderMetric.S_PLUS, "bad", 1))
        assertEquals("", steppedRequirement(FinderMetric.POWER, "", -1))
    }

    @Test
    fun collapsedSectionsStillSubmitTheirSavedRequirements() {
        val draft = FinderDraft("M7")
        val rules =
            FinderRules(
                mapOf(FinderMetric.SOLO to 180000),
                mapOf(DungeonRole.ARCHER to mapOf(FinderMetric.POWER to 1500)),
                setOf(DungeonRole.HEALER),
            )
        draft.restore(FinderPreset(rules, DungeonRole.TANK, false, DungeonRole.entries.toList()))
        assertEquals(false, draft.recordsExpanded)
        assertEquals(false, draft.classesExpanded)
        assertEquals(rules.json(), draft.body(false).getAsJsonObject("rules"))
        assertEquals("tank", draft.body(false).get("leader_class").asString)
    }

    @Test
    fun timeAndCountInputsUseTheBackendUnits() {
        assertEquals(331000, metricInput(FinderMetric.S_PLUS, "5:31"))
        assertEquals(12450, metricInput(FinderMetric.SS, "12.45"))
        assertEquals(1001, metricInput(FinderMetric.SS, "1.001"))
        assertEquals(331025, metricInput(FinderMetric.S_PLUS, "5:31.025"))
        assertEquals(1400, metricInput(FinderMetric.POWER, "1400"))
        assertNull(metricInput(FinderMetric.CATACOMBS, " "))
    }

    @Test
    fun editingSavedThresholdsDoesNotRoundMilliseconds() {
        val rules = FinderRules(mapOf(FinderMetric.S_PLUS to 331025, FinderMetric.SS to 1001))
        val draft = FinderDraft("M7")
        draft.restore(FinderPreset(rules, DungeonRole.MAGE, false, DungeonRole.entries.toList()))
        assertEquals(rules, draft.rules())
    }

    @Test
    fun malformedAndOutOfRangeThresholdsAreRejected() {
        for (raw in listOf("5:60", "5", "-1:30", "1:2", "121:00", "0:00")) assertFails {
            metricInput(FinderMetric.S_PLUS, raw)
        }
        for (raw in listOf("51", "0", "NaN")) assertFails { metricInput(FinderMetric.CLASS, raw) }
        assertFails { metricInput(FinderMetric.SS, "20.01") }
    }

    @Test
    fun editingPreservesBlockedPlayersAndClassRulesWithoutRepublishingIdentity() {
        val json =
            com.google.gson.JsonParser.parseString(
                    javaClass.getResource("/contracts/party-v1.json")!!.readText()
                )
                .asJsonObject
                .getAsJsonObject("state")
        val party =
            dev.mithril.mithrilpf.finder.FinderProtocol.state(
                    json.toString(),
                    json.getAsJsonObject("you").get("uuid").asString,
                )!!
                .party!!
        val draft =
            FinderDraft(
                "M7",
                party.copy(
                    members =
                        party.members.mapIndexed { index, member ->
                            member.copy(leader = index == 0)
                        },
                    slots = party.slots.map { it.copy(role = DungeonRole.MAGE) },
                    rules =
                        FinderRules(
                            mapOf(FinderMetric.POWER to 1300),
                            mapOf(DungeonRole.MAGE to mapOf(FinderMetric.CLASS to 40)),
                        ),
                    blocked = mapOf("b".repeat(32) to "Synthetic"),
                ),
            )
        draft.names = "SyntheticTwo, SyntheticThree SyntheticTwo"
        val body = draft.body(true)
        assertEquals(
            listOf("SyntheticTwo", "SyntheticThree"),
            body.getAsJsonArray("block_names").map { it.asString },
        )
        assertEquals(listOf("b".repeat(32)), body.getAsJsonArray("blocked").map { it.asString })
        assertEquals(false, body.has("leader_class"))
        assertEquals(DungeonRole.MAGE, draft.leader)
        assertEquals(true, draft.duplicates)
        assertEquals(List(5) { DungeonRole.MAGE }, draft.preset().slots)
        assertEquals(40, draft.preset().rules.perClass[DungeonRole.MAGE]?.get(FinderMetric.CLASS))
        assertEquals(party.id, draft.partyId)
    }

    @Test
    fun invalidNamesAndHiddenThresholdsCannotBeSubmitted() {
        val draft = FinderDraft("F7")
        draft.shared[FinderMetric.CATACOMBS] = "bad"
        assertFails { draft.body(false) }
        assertEquals(FinderMetric.CATACOMBS, draft.error)
        draft.shared.clear()
        for (names in listOf("invalid/name", (1..101).joinToString(" ") { "Player$it" })) {
            draft.names = names
            assertFails { draft.body(false) }
            assertEquals(true, draft.invalidNames)
        }
        draft.names = "Valid_Name"
        draft.duplicates = true
        draft.slots = List(5) { DungeonRole.MAGE }
        assertEquals(5, draft.body(false).getAsJsonArray("roles").size())
        assertEquals(false, draft.invalidNames)
        assertNull(draft.error)
        assertEquals("1.1", steppedRequirement(FinderMetric.SS, "1", 1))
        assertEquals("—", metricText(FinderMetric.POWER, null))
        assertEquals("1,400", metricText(FinderMetric.POWER, 1400.0))
        assertEquals("1.23", metricText(FinderMetric.SS, 1230.0))
    }
}
