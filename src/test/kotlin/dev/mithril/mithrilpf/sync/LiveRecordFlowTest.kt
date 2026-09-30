package dev.mithril.mithrilpf.sync

import com.google.gson.JsonObject
import kotlin.test.*

class LiveRecordFlowTest {
    private val id = "a".repeat(43)
    private var now = 1_000_000_000L
    private val calls = mutableListOf<String>()
    private var fail = false
    private var reject = false
    private val flow =
        LiveRecordFlow(
            { path, body, bearer ->
                assertEquals("credential", bearer)
                calls += path
                if (fail) error("Synthetic connection failure")
                if (reject) """{"version":2,"status":"rejected","reason":"death"}"""
                else if (path == "terminal-report")
                    """{"version":2,"status":"accepted","corroboration":"single_report"}"""
                else {
                    val sequence = body["sequence"]?.asInt ?: 0
                    if (sequence > 0) {
                        assertEquals(id, body["attempt_id"].asString)
                        assertEquals(
                            ('b'.code + sequence - 1).toChar().toString().repeat(43),
                            body["nonce"].asString,
                        )
                    }
                    val status = if (body["complete"]?.asBoolean == true) "accepted" else "active"
                    val nonce = ('b'.code + sequence).toChar().toString().repeat(43)
                    """{"version":2,"status":"$status","sequence":$sequence,"attempt_id":"$id","nonce":"$nonce"}"""
                }
            },
            { now },
        )

    private fun event(path: String, run: Long = 1, complete: Boolean = false) =
        RecordEvent(
            run,
            path,
            JsonObject()
                .apply {
                    addProperty("version", 2)
                    addProperty("elapsed_ms", 0)
                    addProperty("complete", complete)
                }
                .toString(),
            now,
        )

    @Test
    fun `each live update uses the previous server acknowledgement and completion ends it`() {
        assertEquals("tracking", flow.send(event("solo-start"), "credential"))
        repeat(2) { assertEquals("tracking", flow.send(event("solo-progress"), "credential")) }
        assertEquals("synced", flow.send(event("solo-progress", complete = true), "credential"))
        assertEquals("local_only", flow.send(event("solo-progress"), "credential"))
        assertEquals(4, calls.size)
    }

    @Test
    fun `connection loss rejects later completion without retrying the timeline`() {
        flow.send(event("solo-start"), "credential")
        fail = true
        assertFails { flow.send(event("solo-progress"), "credential") }
        fail = false
        assertEquals("local_only", flow.send(event("solo-progress", complete = true), "credential"))
        assertEquals(2, calls.size)
    }

    @Test
    fun `rejected or stale events and another run cannot finish an attempt`() {
        flow.send(event("solo-start"), "credential")
        assertEquals("local_only", flow.send(event("solo-progress", run = 2), "credential"))
        val stale = event("solo-progress")
        now += 6_000_000_000L
        assertEquals("local_only", flow.send(stale, "credential"))
        flow.send(event("solo-start", run = 2), "credential")
        reject = true
        assertEquals("local_only", flow.send(event("solo-progress", run = 2), "credential"))
        assertEquals("local_only", flow.send(event("solo-progress", run = 2), "credential"))
        assertEquals(3, calls.size)
    }

    @Test
    fun `terminal report needs no live attempt or second client`() {
        assertEquals("synced", flow.send(event("terminal-report"), "credential"))
        assertEquals(listOf("terminal-report"), calls)
    }
}
