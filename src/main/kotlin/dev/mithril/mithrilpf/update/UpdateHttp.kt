package dev.mithril.mithrilpf.update

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/**
 * This repository's public GitHub releases and Modrinth's public version API/CDN only. No Minecraft
 * credentials, automatic redirects or unbounded reads.
 */
class UpdateHttp {
    fun get(uri: URI, limit: Int): ByteArray {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .use { client ->
                var url = uri
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                try {
                    repeat(4) {
                        require(allowed(url))
                        val remaining = deadline - System.nanoTime()
                        require(remaining > 0) { "Update download timed out" }
                        val request =
                            HttpRequest.newBuilder(url)
                                .timeout(Duration.ofNanos(remaining))
                                .header("User-Agent", "MithrilPF-Updater")
                                .header(
                                    "Accept",
                                    when (url.host) {
                                        "api.github.com" -> "application/vnd.github+json"
                                        "api.modrinth.com" -> "application/json"
                                        else -> "application/octet-stream"
                                    },
                                )
                                .GET()
                                .build()
                        val pending =
                            client.sendAsync(request) { info ->
                                BoundedBytes(if (info.statusCode() == 200) limit else 16384)
                            }
                        val response =
                            try {
                                pending.get(remaining, TimeUnit.NANOSECONDS)
                            } finally {
                                pending.cancel(true)
                            }
                        if (response.statusCode() == 200) return response.body()
                        if (response.statusCode() == 404 && uri.path.endsWith("/latest"))
                            return "[]".toByteArray()
                        require(response.statusCode() in setOf(301, 302, 303, 307, 308)) {
                            "Update HTTP ${response.statusCode()}"
                        }
                        url = url.resolve(response.headers().firstValue("Location").orElseThrow())
                    }
                    error("Too many download redirects")
                } finally {
                    client.shutdownNow()
                }
            }
    }

    companion object {
        fun allowed(uri: URI): Boolean =
            uri.scheme == "https" &&
                uri.userInfo == null &&
                uri.fragment == null &&
                uri.port in setOf(-1, 443) &&
                when (uri.host) {
                    "api.github.com" ->
                        uri.path == "/repos/MithrilAddons/mithrilpf/releases" ||
                            uri.path == "/repos/MithrilAddons/mithrilpf/releases/latest"
                    "github.com" ->
                        uri.path.startsWith("/MithrilAddons/mithrilpf/releases/download/")
                    "release-assets.githubusercontent.com" -> true
                    "api.modrinth.com" ->
                        uri.path.matches(Regex("/v2/project/[A-Za-z0-9]{8}/version"))
                    "cdn.modrinth.com" -> uri.path.startsWith("/data/") && ".." !in uri.path
                    else -> false
                }
    }
}

internal class BoundedBytes(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val delegate = HttpResponse.BodySubscribers.ofByteArray()
    private lateinit var subscription: Flow.Subscription
    private var size = 0

    override fun getBody(): CompletionStage<ByteArray> = delegate.body

    override fun onSubscribe(value: Flow.Subscription) {
        subscription = value
        delegate.onSubscribe(value)
    }

    override fun onNext(items: List<ByteBuffer>) {
        for (item in items) {
            if (item.remaining() > limit - size) {
                subscription.cancel()
                delegate.onError(IllegalStateException("Update response too large"))
                return
            }
            size += item.remaining()
        }
        delegate.onNext(items)
    }

    override fun onError(error: Throwable) = delegate.onError(error)

    override fun onComplete() = delegate.onComplete()
}
