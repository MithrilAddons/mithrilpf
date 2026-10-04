package dev.mithril.mithrilpf.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.mithril.mithrilpf.account.ServiceFailure
import dev.mithril.mithrilpf.soloroom.ReplaySnapshot
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.test.*

class LiveRecordFlowTest {
    @Test
    fun `sync flow encodes the detached replay with the completion map`() {
        val expected =
            JsonParser.parseString(
                    javaClass.getResource("/contracts/run-replay-v1.json")!!.readText()
                )
                .asJsonObject
        var sent: JsonObject? = null
        val worker =
            LiveRecordFlow(
                { path, body, _ ->
                    sent = body.deepCopy()
                    val start = path == "solo-start"
                    """{"version":2,"status":"${if (start) "active" else "accepted"}","sequence":${if (start) 0 else 1},"attempt_id":"${"a".repeat(43)}","nonce":"${"b".repeat(43)}"}"""
                },
                { now },
            )
        worker.send(event("solo-start"), "credential")
        val complete =
            event("solo-progress", complete = true)
                .copy(
                    body = """{"complete":true,"map":{"version":2}}""",
                    replay =
                        ReplaySnapshot(Base64.getDecoder().decode(expected["samples"].asString)),
                )
        assertEquals("synced", worker.send(complete, "credential"))
        assertEquals(expected, assertNotNull(sent)["map"].asJsonObject["replay"])
        assertFalse(complete.body.contains("samples"))
    }

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

    @Test
    fun `queued starts account for age and reject stale or future observations`() {
        for (age in listOf(-1L, 10001L)) {
            val start = event("solo-start")
            now += age * 1_000_000
            assertEquals("local_only", flow.send(start, "credential"))
            assertFalse(flow.accepts(event("solo-progress")))
        }
        assertTrue(calls.isEmpty())
        var elapsed = 0L
        val delayed =
            LiveRecordFlow(
                { _, body, _ ->
                    elapsed = body["elapsed_ms"].asLong
                    """{"version":2,"status":"active","sequence":0,"attempt_id":"$id","nonce":"$id"}"""
                },
                { now },
            )
        val start = event("solo-start")
        now += 1000_000_000
        assertEquals("tracking", delayed.send(start, "credential"))
        assertEquals(1000, elapsed)
        assertTrue(delayed.accepts(event("solo-progress")))
        assertFalse(delayed.accepts(event("solo-progress", run = 2)))
        assertTrue(delayed.accepts(event("terminal-report")))
    }

    @Test
    fun `invalid acknowledgements cannot advance or complete a live attempt`() {
        val valid =
            """{"version":2,"status":"active","sequence":0,"attempt_id":"$id","nonce":"$id"}"""
        val bad =
            listOf(
                "{}",
                valid.replace("\"version\":2", "\"version\":1"),
                valid.replace("active", "unknown"),
                valid.replace("active", "accepted"),
                valid.replace("\"sequence\":0", "\"sequence\":2"),
                valid.replace("\"attempt_id\":\"$id\"", "\"attempt_id\":\"short\""),
                valid.replace("\"nonce\":\"$id\"", "\"nonce\":\"short\""),
            )
        for (response in bad) {
            val broken = LiveRecordFlow({ _, _, _ -> response }, { now })
            assertFails { broken.send(event("solo-start"), "credential") }
            assertFalse(broken.accepts(event("solo-progress")))
        }
        var response = valid
        val switched = LiveRecordFlow({ _, _, _ -> response }, { now })
        switched.send(event("solo-start"), "credential")
        response =
            valid
                .replace("\"sequence\":0", "\"sequence\":1")
                .replace("\"attempt_id\":\"$id\"", "\"attempt_id\":\"${"z".repeat(43)}\"")
        assertFails { switched.send(event("solo-progress"), "credential") }
        assertFalse(switched.accepts(event("solo-progress")))
    }

    @Test
    fun `transient progress failures retry the exact nonce sequence and completion body`() {
        for (failure in
            listOf(
                IOException(),
                TimeoutException(),
                ExecutionException(IOException()),
                ServiceFailure(502),
                ServiceFailure(503),
                ServiceFailure(504),
            )) {
            var requests = 0
            var first: String? = null
            val retrying =
                LiveRecordFlow(
                    { path, body, _ ->
                        if (path == "solo-start") {
                            """{"version":2,"status":"active","sequence":0,"attempt_id":"$id","nonce":"$id"}"""
                        } else {
                            if (first == null) first = body.toString()
                            assertEquals(first, body.toString())
                            requests++
                            if (requests < 3) throw failure
                            """{"version":2,"status":"accepted","sequence":1,"attempt_id":"$id","nonce":"$id"}"""
                        }
                    },
                    { now },
                )
            retrying.send(event("solo-start"), "credential")
            assertEquals(
                "synced",
                retrying.send(event("solo-progress", complete = true), "credential"),
            )
            assertEquals(3, requests)
            assertFalse(retrying.accepts(event("solo-progress")))
        }
    }

    @Test
    fun `persistent failures stop after bounded retries and never retry a stale timeline`() {
        for (duration in listOf(0L, 2_000_000_000L, 4_000_000_000L)) {
            var requests = 0
            val retrying =
                LiveRecordFlow(
                    { path, _, _ ->
                        if (path == "solo-start")
                            """{"version":2,"status":"active","sequence":0,"attempt_id":"$id","nonce":"$id"}"""
                        else {
                            requests++
                            now += duration
                            throw IOException()
                        }
                    },
                    { now },
                )
            retrying.send(event("solo-start"), "credential")
            assertFailsWith<IOException> { retrying.send(event("solo-progress"), "credential") }
            assertEquals(
                if (duration == 0L) 3 else if (duration == 2_000_000_000L) 2 else 1,
                requests,
            )
            assertFalse(retrying.accepts(event("solo-progress")))
        }
    }

    @Test
    fun `authentication validation cancellation and non-progress requests are never retried`() {
        for (failure in
            listOf(
                ServiceFailure(401),
                ServiceFailure(422),
                IllegalArgumentException(),
                InterruptedException(),
                ExecutionException(IllegalStateException()),
            )) {
            var requests = 0
            val retrying =
                LiveRecordFlow(
                    { path, _, _ ->
                        if (path == "solo-start")
                            """{"version":2,"status":"active","sequence":0,"attempt_id":"$id","nonce":"$id"}"""
                        else {
                            requests++
                            throw failure
                        }
                    },
                    { now },
                )
            retrying.send(event("solo-start"), "credential")
            assertFails { retrying.send(event("solo-progress"), "credential") }
            assertEquals(1, requests)
        }
        for (path in listOf("solo-start", "terminal-report")) {
            var requests = 0
            val retrying =
                LiveRecordFlow(
                    { _, _, _ ->
                        requests++
                        throw IOException()
                    },
                    { now },
                )
            assertFails { retrying.send(event(path), "credential") }
            assertEquals(1, requests)
        }
    }
}
