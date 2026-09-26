package dev.mithril.mithrilpf.update

import kotlin.test.*
import org.junit.Test

class UpdateDistributionTest {
    @Test
    fun onlyMatchingOfficialMarkerEnablesUpdates() {
        fun parse(text: String) = UpdateDistribution.official(text.byteInputStream(), "0.2.0-rc.4")
        assertTrue(parse("version=0.2.0-rc.4\nofficialRelease=true"))
        assertFalse(parse("version=0.2.0-rc.4\nofficialRelease=false"))
        assertFalse(parse("version=0.2.0-rc.3\nofficialRelease=true"))
        assertFalse(parse("version=0.2.0-rc.4"))
        assertFalse(parse("officialRelease=true"))
        assertFalse(parse("a".repeat(1025)))
        assertFalse(UpdateDistribution.official(null, "0.2.0-rc.4"))
    }
}
