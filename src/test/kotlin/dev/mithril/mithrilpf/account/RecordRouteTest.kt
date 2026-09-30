package dev.mithril.mithrilpf.account

import kotlin.test.*

class RecordRouteTest {
    @Test
    fun `record routes require a scoped credential while proofs never send one`() {
        val token = "a".repeat(43)
        for (path in listOf("sync-challenge", "sync-verify")) {
            assertEquals("auth/$path", LinkTransport.syncPath(path, null))
            assertFailsWith<IllegalArgumentException> { LinkTransport.syncPath(path, token) }
        }
        for (path in listOf("solo-start", "solo-progress", "terminal-report")) {
            assertEquals("records/$path", LinkTransport.syncPath(path, token))
            assertFailsWith<IllegalArgumentException> { LinkTransport.syncPath(path, null) }
            assertFailsWith<IllegalArgumentException> { LinkTransport.syncPath(path, "invalid") }
        }
        for (path in listOf("records", "../auth/verify", "https://evil.invalid")) assertFailsWith<
            IllegalArgumentException
        > {
            LinkTransport.syncPath(path, token)
        }
    }
}
