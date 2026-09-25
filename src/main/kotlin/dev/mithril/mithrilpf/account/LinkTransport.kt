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
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .use { client ->
                val request =
                    HttpRequest.newBuilder(URI("https://mithril.foo/api/v1/auth/$path"))
                        .timeout(Duration.ofSeconds(12))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build()
                val response = client.send(request) { BoundedBody() }
                check(response.statusCode() == 200) { "Account service unavailable" }
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
