package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock

/** Mojang keeps one pending server proof per account. Do not race link/sync/party proofs. */
object OwnershipProof {
    private val lock = ReentrantLock()
    private val random = SecureRandom()

    fun nonce(): String = ByteArray(32).also(random::nextBytes).toHex()

    /** Neither peer may choose a Minecraft login hash; the server also supplies freshness. */
    fun serverId(challenge: JsonObject, nonce: String, uuid: String, scope: String): String {
        val serverNonce = challenge.get("server_nonce")?.asString ?: error("Missing proof nonce")
        require(serverNonce.matches(Regex("[0-9a-f]{64}")))
        val input = "mithrilpf:ownership:v2:$scope:$uuid:$nonce:$serverNonce"
        val expected =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
                .toHex()
                .take(39)
        require(challenge.get("server_id")?.asString == expected)
        return expected
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun <T> serialized(action: () -> T): T {
        lock.lockInterruptibly()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }
}
