package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import java.security.MessageDigest

/** Synthetic backend response, derived independently from the request. */
fun proofFixture(body: JsonObject, scope: String, token: String): String {
    val nonce = "d".repeat(64)
    val value =
        "mithrilpf:ownership:v2:$scope:${body["uuid"].asString}:${body["client_nonce"].asString}:$nonce"
    val id =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(39)
    return """{"version":1,"challenge_id":"$token","server_id":"$id","server_nonce":"$nonce"}"""
}
