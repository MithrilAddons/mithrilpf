package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

object LinkTransport {
    fun post(path: String, body: JsonObject): String {
        require(path in setOf("challenge", "verify", "link-status"))
        return request("auth/$path", body, null)
    }

    fun syncPost(path: String, body: JsonObject, token: String?): String {
        require(path in setOf("sync-challenge", "sync-verify", "sync-records"))
        require((path == "sync-records") == (token != null))
        if (token != null) require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        return request("auth/$path", body, token)
    }

    fun partyPost(path: String, body: JsonObject, token: String?): String {
        require(path in setOf("party-challenge", "party-verify", "presence", "roster", "invite"))
        val proof = path.startsWith("party-")
        require(proof == (token == null))
        if (token != null) require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        return request(if (proof) "auth/$path" else "party/mod/$path", body, token)
    }

    private fun request(path: String, body: JsonObject, token: String?): String {
        require(body.toString().toByteArray(Charsets.UTF_8).size <= 4096)
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .use { client ->
                val request =
                    HttpRequest.newBuilder(URI("https://mithril.foo/api/v1/$path"))
                        .timeout(Duration.ofSeconds(12))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .apply { if (token != null) header("Authorization", "Bearer $token") }
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build()
                val response = client.send(request) { BoundedBody() }
                if (response.statusCode() != 200) throw ServiceFailure(response.statusCode())
                return response.body()
            }
    }

    /** Bounds allocation as data arrives, rather than after receiving the body. */
    private class BoundedBody : HttpResponse.BodySubscriber<String> {
        private val delegate = HttpResponse.BodySubscribers.ofString(Charsets.UTF_8)
        private lateinit var subscription: Flow.Subscription
        private var size = 0

        override fun getBody(): CompletionStage<String> = delegate.body

        override fun onSubscribe(value: Flow.Subscription) {
            subscription = value
            delegate.onSubscribe(value)
        }

        override fun onNext(items: List<ByteBuffer>) {
            for (item in items) {
                if (item.remaining() > 16384 - size) {
                    subscription.cancel()
                    delegate.onError(IllegalStateException("Response too large"))
                    return
                }
                size += item.remaining()
            }
            delegate.onNext(items)
        }

        override fun onError(error: Throwable) = delegate.onError(error)

        override fun onComplete() = delegate.onComplete()
    }
}

class ServiceFailure(val statusCode: Int) : Exception("Mithril service unavailable")
