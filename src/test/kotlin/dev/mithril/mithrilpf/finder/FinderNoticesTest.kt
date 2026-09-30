package dev.mithril.mithrilpf.finder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinderNoticesTest {
    @Test
    fun primesOldNoticesAndDoesNotRepeatAfterRetriesOrAccountChanges() {
        val notices = FinderNotices()
        val old = FinderNotice("one", "placed", "party0000001", null)
        val fresh = old.copy(id = "two", kind = "party_full")
        assertTrue(notices.accept(listOf(old)).isEmpty())
        assertEquals(listOf(fresh), notices.accept(listOf(old, fresh)))
        assertTrue(notices.accept(listOf(fresh)).isEmpty())
        notices.reset()
        assertTrue(notices.accept(listOf(fresh)).isEmpty())
    }
}
