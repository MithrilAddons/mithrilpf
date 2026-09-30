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

    fun authenticate(
        uuid: String,
        name: String,
        receiptToken: String,
        proveOwnership: (String) -> Unit,
    ): String {
        require(uuid.matches(Regex("[0-9a-f]{32}")))
        require(name.matches(Regex("[A-Za-z0-9_]{1,16}")))
        require(receiptToken.matches(Regex("[A-Za-z0-9_-]{43}")))
        if (account != uuid || receipt != receiptToken) {
            account = uuid
            receipt = receiptToken
            session = null
        }
        try {
            check(!Thread.currentThread().isInterrupted)
            if (session == null || clock() >= expires) {
                OwnershipProof.serialized {
                    val nonce = OwnershipProof.nonce()
                    val challenge =
                        parse(
                            post(
                                "sync-challenge",
                                JsonObject().apply {
                                    addProperty("version", 1)
                                    addProperty("client_nonce", nonce)
                                    addProperty("uuid", uuid)
                                    addProperty("name", name)
                                    addProperty("receipt_token", receiptToken)
                                },
                                null,
                            )
                        )
                    val id = token(challenge, "challenge_id")
                    val serverId = OwnershipProof.serverId(challenge, nonce, uuid, "sync")
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
            return requireNotNull(session)
        } catch (exception: Exception) {
            session = null
            throw exception
        }
    }

    fun process(
        uuid: String,
        name: String,
        receiptToken: String?,
        event: RecordEvent?,
        live: LiveRecordFlow,
        proveOwnership: (String) -> Unit,
    ): String? {
        if (event != null && !live.accepts(event)) return "local_only"
        if (receiptToken == null) {
            clear()
            live.clear()
            return "unlinked"
        }
        val credential = authenticate(uuid, name, receiptToken, proveOwnership)
        return event?.let { live.send(it, credential) }
    }

    fun clear() {
        account = ""
        receipt = ""
        session = null
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
