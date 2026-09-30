package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Native sign-in uses a distinct nonce-bound proof; Minecraft credentials only go to Mojang. */
class DeviceFlow(private val request: (String, JsonObject?, String?) -> String) {
    fun signIn(uuid: String, name: String, prove: (String) -> Unit): DeviceSession =
        OwnershipProof.serialized {
            require(uuid.matches(Regex("[0-9a-f]{32}")))
            require(name.matches(Regex("[A-Za-z0-9_]{1,16}")))
            val nonce = OwnershipProof.nonce()
            val challenge =
                parse(
                    request(
                        "auth/device-challenge",
                        JsonObject().apply {
                            addProperty("version", 1)
                            addProperty("uuid", uuid)
                            addProperty("name", name)
                            addProperty("client_nonce", nonce)
                        },
                        null,
                    )
                )
            val id = token(challenge, "challenge_id")
            val serverId = OwnershipProof.serverId(challenge, nonce, uuid, "device")
            check(!Thread.currentThread().isInterrupted)
            prove(serverId)
            check(!Thread.currentThread().isInterrupted)
            val response =
                parse(
                    request(
                        "auth/device-verify",
                        JsonObject().apply {
                            addProperty("challenge_id", id)
                        },
                        null,
                    )
                )
            require(response.getAsJsonObject("user").get("uuid").asString == uuid)
            val lifetime = response.get("expires_in_seconds").asLong
            require(lifetime in 1..2_592_000L)
            DeviceSession(
                token(response, "device_token"),
                token(response, "receipt_token"),
                System.currentTimeMillis() / 1000 + lifetime,
            )
        }

    fun check(uuid: String, session: DeviceSession) {
        val response = parse(request("auth/device-session", null, session.token))
        require(response.getAsJsonObject("user").get("uuid").asString == uuid)
    }

    fun signOut(session: DeviceSession) {
        try {
            parse(request("auth/device-logout", JsonObject(), session.token))
        } catch (failure: ServiceFailure) {
            // An expired/revoked credential is already signed out, including a lost prior reply.
            if (failure.statusCode != 401) throw failure
        }
    }

    private fun parse(value: String): JsonObject {
        require(value.length <= 16384)
        val result = JsonParser.parseString(value).asJsonObject
        require(result.get("version")?.toString() == "1")
        return result
    }

    private fun token(value: JsonObject, key: String): String {
        val result = value.get(key).asString
        require(result.matches(Regex("[A-Za-z0-9_-]{43}")))
        return result
    }
}

// Deliberately not a data class: accidental logging must not print credentials.
class DeviceSession(val token: String, val receipt: String, val expires: Long)
