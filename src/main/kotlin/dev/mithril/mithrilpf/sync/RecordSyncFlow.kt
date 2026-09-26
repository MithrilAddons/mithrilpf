package dev.mithril.mithrilpf.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.mithril.mithrilpf.account.OwnershipProof

/**
 * Worker-owned scoped session. Receipts alone never authorize writes; game tokens stay at Mojang.
 */
class RecordSyncFlow(
    private val post: (String, JsonObject, String?) -> String,
    private val clock: () -> Long = System::nanoTime,
) {
    private var account = ""
    private var receipt = ""
    private var session: String? = null
    private var expires = 0L
    private var uploaded: RecordSnapshot? = null

    fun sync(
        uuid: String,
        name: String,
        receiptToken: String,
        snapshot: RecordSnapshot,
        proveOwnership: (String) -> Unit,
    ) {
        require(uuid.matches(Regex("[0-9a-f]{32}")))
        require(name.matches(Regex("[A-Za-z0-9_]{1,16}")))
        require(receiptToken.matches(Regex("[A-Za-z0-9_-]{43}")))
        if (account != uuid || receipt != receiptToken) {
            account = uuid
            receipt = receiptToken
            session = null
            uploaded = null
        }
        if (snapshot.records.isEmpty() || snapshot == uploaded) return
        try {
            check(!Thread.currentThread().isInterrupted)
            if (session == null || clock() >= expires) {
                OwnershipProof.serialized {
                    val challenge =
                        parse(
                            post(
                                "sync-challenge",
                                JsonObject().apply {
                                    addProperty("version", 1)
                                    addProperty("uuid", uuid)
                                    addProperty("name", name)
                                    addProperty("receipt_token", receiptToken)
                                },
                                null,
                            )
                        )
                    val id = token(challenge, "challenge_id")
                    val serverId = challenge.get("server_id")?.asString ?: error("Missing proof")
                    require(serverId.matches(Regex("[0-9a-f]{39}")))
                    check(!Thread.currentThread().isInterrupted)
                    proveOwnership(serverId)
                    check(!Thread.currentThread().isInterrupted)
                    val verified =
                        parse(
                            post(
                                "sync-verify",
                                JsonObject().apply {
                                    addProperty("challenge_id", id)
                                    addProperty("receipt_token", receiptToken)
                                },
                                null,
                            )
                        )
                    require(verified.getAsJsonObject("user").get("uuid").asString == uuid)
                    require(verified.get("expires_in_seconds")?.toString() == "900")
                    session = token(verified, "sync_token")
                    expires = clock() + 840_000_000_000L
                }
            }
            check(!Thread.currentThread().isInterrupted)
            val result = parse(post("sync-records", snapshot.json(), session))
            require(result.getAsJsonObject("user").get("uuid").asString == uuid)
            require(result.get("accepted")?.toString() == snapshot.records.size.toString())
            uploaded = snapshot
        } catch (exception: Exception) {
            session = null
            throw exception
        }
    }

    fun clear() {
        account = ""
        receipt = ""
        session = null
        uploaded = null
    }

    private fun parse(text: String): JsonObject {
        require(text.length <= 16384)
        return JsonParser.parseString(text).asJsonObject.also {
            require(it.get("version")?.toString() == "1")
        }
    }

    private fun token(body: JsonObject, field: String): String =
        requireNotNull(body.get(field)?.asString).also {
            require(it.matches(Regex("[A-Za-z0-9_-]{43}")))
        }
}
