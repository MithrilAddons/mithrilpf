package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/** No Minecraft access token enters this protocol. Ownership proof goes only to Mojang. */
class LinkFlow(private val post: (String, JsonObject) -> String) {
    fun run(uuid: String, name: String, proveOwnership: (String) -> Unit): IssuedLink =
        OwnershipProof.serialized {
            require(uuid.matches(Regex("[0-9a-f]{32}")))
            require(name.matches(Regex("[A-Za-z0-9_]{1,16}")))
            val challenge =
                parse(
                    post(
                        "challenge",
                        JsonObject().apply {
                            addProperty("version", 1)
                            addProperty("uuid", uuid)
                            addProperty("name", name)
                        },
                    )
                )
            val id = token(challenge, "challenge_id")
            val serverId = challenge.get("server_id")?.asString ?: error("Missing challenge")
            require(serverId.matches(Regex("[0-9a-f]{39}")))
            check(!Thread.currentThread().isInterrupted)
            proveOwnership(serverId)
            check(!Thread.currentThread().isInterrupted)
            val verified =
                parse(post("verify", JsonObject().apply { addProperty("challenge_id", id) }))
            val code = verified.get("user_code")?.asString
            require(code == null || code.matches(Regex("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}")))
            val lifetime = verified.get("expires_in_seconds")?.asInt ?: 300
            require(lifetime in 1..300)
            IssuedLink(
                URI("https://mithril.foo/link#${token(verified, "link_token")}"),
                if (verified.has("receipt_token")) token(verified, "receipt_token") else null,
                code,
                lifetime,
            )
        }

    fun status(receipt: String, uuid: String): String {
        require(receipt.matches(Regex("[A-Za-z0-9_-]{43}")))
        val response =
            parse(post("link-status", JsonObject().apply { addProperty("token", receipt) }))
        val status = response.get("status")?.asString
        require(status in setOf("pending", "linked", "expired"))
        if (status == "linked")
            require(response.getAsJsonObject("user").get("uuid").asString == uuid)
        return status!!
    }

    private fun parse(body: String): JsonObject {
        require(body.length <= 16384)
        val result = JsonParser.parseString(body).asJsonObject
        require(result.get("version")?.toString() == "1")
        return result
    }

    private fun token(body: JsonObject, field: String): String {
        val value = body.get(field)?.asString ?: error("Missing token")
        require(value.matches(Regex("[A-Za-z0-9_-]{43}")))
        return value
    }
}

class IssuedLink(
    val uri: URI,
    val receipt: String?,
    val code: String? = null,
    val lifetime: Int = 300,
)
