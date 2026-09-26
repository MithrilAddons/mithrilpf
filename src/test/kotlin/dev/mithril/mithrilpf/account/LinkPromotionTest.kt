package dev.mithril.mithrilpf.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class LinkPromotionTest {
    private val active = LinkReceipt("a".repeat(43), true)
    private val pending = LinkReceipt("b".repeat(43), false)

    @Test
    fun `pending and expired replacements retain the original receipt`() {
        for (status in listOf("pending", "expired")) {
            assertEquals(active, LinkPromotion.saved(active, pending, status, true))
            assertNull(LinkPromotion.saved(null, pending, status, true))
        }
    }

    @Test
    fun `only confirmed replacement is promoted`() {
        assertEquals(
            pending.copy(confirmed = true),
            LinkPromotion.saved(active, pending, "linked", true),
        )
        assertFails { LinkPromotion.saved(active, pending, "other", true) }
    }

    @Test
    fun `revoked active connection is still cleared and legacy pending receipt can confirm`() {
        assertNull(LinkPromotion.saved(active, active, "expired", false))
        assertEquals(
            pending.copy(confirmed = true),
            LinkPromotion.saved(pending, pending, "linked", false),
        )
    }
}
