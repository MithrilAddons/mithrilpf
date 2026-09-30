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
}
