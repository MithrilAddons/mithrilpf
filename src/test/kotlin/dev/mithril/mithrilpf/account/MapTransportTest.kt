package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import kotlin.test.*

class MapTransportTest {
    @Test
    fun `larger completion payloads are bounded by UTF8 bytes only on progress route`() {
        val body = JsonObject().apply { addProperty("map", "x".repeat(6000)) }
        LinkTransport.validateBody("records/solo-progress", body)
        for (path in listOf("records/solo-start", "records/terminal-report", "auth/verify")) {
            assertFailsWith<IllegalArgumentException> { LinkTransport.validateBody(path, body) }
        }
        body.addProperty("map", "界".repeat(12000))
        assertFailsWith<IllegalArgumentException> {
            LinkTransport.validateBody("records/solo-progress", body)
        }
        LinkTransport.validateBody("auth/session", null)
    }
}
