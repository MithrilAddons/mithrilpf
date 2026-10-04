package dev.mithril.mithrilpf.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.mithril.mithrilpf.soloroom.ReplaySnapshot

/** Immutable client-thread observations; no Minecraft objects cross into the worker. */
data class RecordEvent(
    val run: Long,
    val path: String,
    val body: String,
    val capturedNanos: Long,
    val replay: ReplaySnapshot? = null,
)

/** A failed live exchange is final for that attempt. Local PB storage is independent. */
class LiveRecordFlow(
    private val post: (String, JsonObject, String?) -> String,
    private val clock: () -> Long = System::nanoTime,
) {
    private var run = -1L
    private var attempt: String? = null
    private var nonce: String? = null
    private var sequence = 0

    fun accepts(event: RecordEvent): Boolean =
        event.path != "solo-progress" || (run == event.run && attempt != null)

    fun send(event: RecordEvent, credential: String): String {
        val body = JsonParser.parseString(event.body).asJsonObject
        if (event.path == "solo-start") {
            clear()
            run = event.run
            val age = (clock() - event.capturedNanos) / 1_000_000
            if (age !in 0..10000) return "local_only"
            body.addProperty("elapsed_ms", body["elapsed_ms"].asLong + age)
        } else if (event.path == "solo-progress") {
            if (run != event.run || attempt == null) return "local_only"
            if (clock() - event.capturedNanos > 5_000_000_000L) {
                clear()
                return "local_only"
            }
            body.addProperty("attempt_id", attempt)
            body.addProperty("nonce", nonce)
            body.addProperty("sequence", sequence + 1)
        }
        try {
            event.replay?.let { body.getAsJsonObject("map")?.add("replay", it.encode()) }
            val response = JsonParser.parseString(post(event.path, body, credential)).asJsonObject
            require(response["version"]?.asInt == 2)
            val status = response["status"]?.asString
            if (status == "rejected") {
                clear()
                return "local_only"
            }
            require(status == "active" || status == "accepted")
            require(
                (status == "accepted") ==
                    (event.path == "terminal-report" || body["complete"]?.asBoolean == true)
            )
            if (event.path != "terminal-report") acknowledge(event, response, status)
            return if (status == "accepted") "synced" else "tracking"
        } catch (error: Exception) {
            clear()
            throw error
        }
    }

    private fun acknowledge(event: RecordEvent, response: JsonObject, status: String) {
        val id = response["attempt_id"].asString
        val nextNonce = response["nonce"].asString
        val nextSequence = response["sequence"].asInt
        require(id.matches(Regex("[A-Za-z0-9_-]{43}")))
        require(nextNonce.matches(Regex("[A-Za-z0-9_-]{43}")))
        require(nextSequence == if (event.path == "solo-start") 0 else sequence + 1)
        require(attempt == null || attempt == id)
        attempt = if (status == "accepted") null else id
        nonce = nextNonce
        sequence = nextSequence
    }

    fun clear() {
        run = -1
        attempt = null
        nonce = null
        sequence = 0
    }
}
