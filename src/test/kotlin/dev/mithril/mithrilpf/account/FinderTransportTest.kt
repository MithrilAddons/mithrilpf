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

    @Test
    fun nativeRoutesRejectCredentialMixupsAndUnapprovedDestinations() {
        val token = "t".repeat(43)
        for (path in listOf("auth/device-challenge", "auth/device-verify")) {
            LinkTransport.validateFinderPath(path, null)
            assertFails { LinkTransport.validateFinderPath(path, token) }
        }
        val authenticated =
            listOf(
                "auth/device-session",
                "auth/device-logout",
                "auth/device-player-card",
                "auth/device-erase",
                "party/client/state",
                "party/client/look",
                "party/client/stop-looking",
                "party/client/reserve",
                "party/client/leave",
                "party/client/publish",
                "party/client/edit",
                "party/client/pause",
                "party/client/unlist",
                "party/client/remove",
                "party/client/chat",
                "party/client/chat/report",
                "party/client/listings?floor=F7",
                "party/client/listings?floor=M7",
                "party/client/listings/party0000001",
            )
        for (path in authenticated) {
            LinkTransport.validateFinderPath(path, token)
            assertFails { LinkTransport.validateFinderPath(path, null) }
            assertFails { LinkTransport.validateFinderPath(path, "bad\r\nAuthorization: evil") }
        }
        for (path in
            listOf(
                "https://evil.invalid/",
                "../auth/device-session",
                "party/client/listings?floor=F6",
                "party/client/listings/party0000001?extra=1",
            )) assertFails { LinkTransport.validateFinderPath(path, token) }
    }
}
