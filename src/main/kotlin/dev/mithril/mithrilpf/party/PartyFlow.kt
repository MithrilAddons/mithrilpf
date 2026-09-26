package dev.mithril.mithrilpf.party

import com.google.gson.JsonObject
import dev.mithril.mithrilpf.account.OwnershipProof
import dev.mithril.mithrilpf.account.ServiceFailure
import java.util.concurrent.TimeUnit

/** Worker-owned credential; no file persistence and no authority to upload PBs or sign in. */
class PartyFlow(
    private val post: (String, JsonObject, String?) -> String,
    private val clock: () -> Long = System::nanoTime,
) {
    private var account = ""
    private var receipt = ""
    private var token: String? = null
    private var expires = 0L

    fun exchange(
        uuid: String,
        name: String,
        receiptToken: String,
        online: Boolean,
        report: PartyReport?,
        proveOwnership: (String) -> Unit,
    ): PartyReply {
        require(Regex("[0-9a-f]{32}").matches(uuid))
        require(Regex("[A-Za-z0-9_]{1,16}").matches(name))
        require(Regex("[A-Za-z0-9_-]{43}").matches(receiptToken))
        if (account != uuid || receipt != receiptToken) {
            clear()
            account = uuid
            receipt = receiptToken
        }
        check(!Thread.currentThread().isInterrupted)
        if (token == null || clock() >= expires)
            OwnershipProof.serialized {
                val challenge =
                    PartyProtocol.parse(
                        post(
                            "party-challenge",
                            JsonObject().apply {
                                addProperty("version", 1)
                                addProperty("uuid", uuid)
                                addProperty("name", name)
                                addProperty("receipt_token", receiptToken)
                            },
                            null,
                        )
                    )
                val id = credential(challenge, "challenge_id")
                val serverId = challenge.get("server_id").asString
                require(Regex("[0-9a-f]{39}").matches(serverId))
                check(!Thread.currentThread().isInterrupted)
                proveOwnership(serverId)
                check(!Thread.currentThread().isInterrupted)
                val verified =
                    PartyProtocol.parse(
                        post(
                            "party-verify",
                            JsonObject().apply {
                                addProperty("challenge_id", id)
                                addProperty("receipt_token", receiptToken)
                            },
                            null,
                        )
                    )
                require(verified.getAsJsonObject("user").get("uuid").asString == uuid)
                require(verified.get("expires_in_seconds").toString() == "2592000")
                token = credential(verified, "party_token")
                expires = clock() + TimeUnit.SECONDS.toNanos(2591940)
            }
        try {
            check(!Thread.currentThread().isInterrupted)
            var reply =
                PartyProtocol.reply(
                    PartyProtocol.parse(
                        post(
                            "presence",
                            JsonObject().apply {
                                addProperty("version", 1)
                                addProperty("online", online)
                            },
                            token,
                        )
                    )
                )
            val current = reply.party
            if (
                online &&
                    report != null &&
                    current?.id == report.party.id &&
                    current.generation == report.party.generation &&
                    current.youLead &&
                    current.accepts(report.roster)
            ) {
                check(!Thread.currentThread().isInterrupted)
                reply =
                    PartyProtocol.reply(
                        PartyProtocol.parse(
                            post(
                                if (report.retry == null) "roster" else "invite",
                                report.json(),
                                token,
                            )
                        )
                    )
            }
            return reply
        } catch (failure: ServiceFailure) {
            if (failure.statusCode == 401) token = null
            throw failure
        }
    }

    fun clear() {
        token = null
        account = ""
        receipt = ""
    }

    private fun credential(body: JsonObject, key: String) =
        body.get(key).asString.also {
            require(Regex("[A-Za-z0-9_-]{43}").matches(it))
        }
}
