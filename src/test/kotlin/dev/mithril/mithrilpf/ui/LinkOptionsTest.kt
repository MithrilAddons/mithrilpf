package dev.mithril.mithrilpf.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkOptionsTest {
    @Test
    fun `default offers browser only and expansion exposes alternatives`() {
        val simple = LinkOptions.from(false, true, "opened", "ABCD-EFGH")
        assertEquals(LinkAction.OPEN, simple.action)
        assertFalse(simple.showAlternatives)
        assertNull(simple.code)
        val expanded = LinkOptions.from(true, true, "opened", "ABCD-EFGH")
        assertEquals(LinkAction.COPY, expanded.action)
        assertTrue(expanded.showAlternatives)
        assertEquals("ABCD-EFGH", expanded.code)
        assertEquals(simple, LinkOptions.from(false, true, "opened", "ABCD-EFGH"))
    }

    @Test
    fun `old backend retains QR and copy without pretending a text code exists`() {
        val options = LinkOptions.from(true, true, "opened", null)
        assertTrue(options.showAlternatives)
        assertEquals(LinkAction.COPY, options.action)
        assertNull(options.code)
    }

    @Test
    fun `expired failed and completed attempts replace main action`() {
        for (expanded in listOf(false, true)) {
            for (status in listOf("code_expired", "failed", "busy")) {
                val options = LinkOptions.from(expanded, false, status, null)
                assertEquals(LinkAction.REFRESH, options.action)
                assertFalse(options.showAlternatives)
                assertNull(options.code)
            }
            assertEquals(
                LinkAction.WEBSITE,
                LinkOptions.from(expanded, false, "linked", null).action,
            )
        }
    }
}
