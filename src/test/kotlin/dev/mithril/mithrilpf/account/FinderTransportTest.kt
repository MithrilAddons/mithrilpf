package dev.mithril.mithrilpf.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class FinderTransportTest {
    @Test
    fun installedClientsAlwaysUseTheProductionOrigin() {
        assertEquals(
            "https://mithril.foo/api/v1/",
            LinkTransport.origin(false, "http://evil.invalid/"),
        )
        assertEquals(
            "http://127.0.0.1:8797/api/v1/",
            LinkTransport.origin(true, "http://127.0.0.1:8797/api/v1/"),
        )
        for (value in
            listOf(
                "http://localhost:8797/api/v1/",
                "https://evil.invalid/",
                "http://127.0.0.1:65536/api/v1/",
                "http://127.0.0.1:8797/api/v1/?token=",
            )) assertFails { LinkTransport.origin(true, value) }
    }

    @Test
    fun serverMessagesAreMappedToKnownSafeLabelsOnly() {
        assertEquals("muted", LinkTransport.failureReason("""{"detail":"Chat muted"}"""))
        assertEquals(
            "unknown_names",
            LinkTransport.failureReason(
                """{"detail":{"code":"unknown_names","names":["Synthetic"]}}"""
            ),
        )
        assertNull(LinkTransport.failureReason("""{"detail":"arbitrary untrusted text"}"""))
        assertNull(LinkTransport.failureReason("broken"))
    }
}
