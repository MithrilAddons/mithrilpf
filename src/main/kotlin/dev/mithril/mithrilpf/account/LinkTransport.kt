package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import net.fabricmc.loader.api.FabricLoader

object LinkTransport {
    internal fun origin(development: Boolean, override: String?): String {
        if (!development || override == null) return "https://mithril.foo/api/v1/"
        require(override.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}/api/v1/")))
        require(URI(override).port in 1..65535)
        return override
    }

    fun post(path: String, body: JsonObject): String {
        require(path in setOf("challenge", "verify", "link-status"))
        return request("auth/$path", body, null)
    }

    fun syncPost(path: String, body: JsonObject, token: String?): String =
        request(
            syncPath(path, token),
            body,
            token,
            timeout = if (path == "solo-progress") 2 else 12,
        )

    internal fun syncPath(path: String, token: String?): String {
        require(
            path in
                setOf(
                    "sync-challenge",
                    "sync-verify",
                    "solo-start",
                    "solo-progress",
                    "terminal-report",
                )
        )
        require(path.startsWith("sync-") == (token == null))
        if (token != null) require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        return if (path.startsWith("sync-")) "auth/$path" else "records/$path"
    }

    fun partyPost(path: String, body: JsonObject, token: String?): String {
        require(path in setOf("party-challenge", "party-verify", "presence", "roster", "invite"))
        val proof = path.startsWith("party-")
        require(proof == (token == null))
        if (token != null) require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        return request(if (proof) "auth/$path" else "party/mod/$path", body, token)
    }

    fun chatPost(path: String, body: JsonObject, token: String): String {
        require(path in setOf("state", "send"))
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        return request("party/mod/chat/$path", body, token, 262144, if (path == "state") 35 else 12)
    }

    fun finderRequest(path: String, body: JsonObject?, token: String?): String {
        validateFinderPath(path, token)
        return request(path, body, token, 1048576, if (path == "party/client/state") 35 else 12)
    }

    /** Curator's item list is the largest response the mod reads, about 200 KiB. */
    fun gameRequest(path: String, body: JsonObject?, token: String): String {
        validateGamePath(path, body)
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        return request(path, body, token, 1048576)
    }

    internal fun validateGamePath(path: String, body: JsonObject?) {
        val reads =
            setOf("games/curator/today", "games/curator/leaderboard", "games/curator/catalog")
        require(
            if (body == null)
                path in reads ||
                    path.matches(Regex("games/curator/catalog\\?version=[A-Za-z0-9:._%-]{1,64}"))
            else path == "games/curator/guess"
        )
    }

    internal fun validateFinderPath(path: String, token: String?) {
        val proof = path in setOf("auth/device-challenge", "auth/device-verify")
        val allowed =
            path in
                setOf(
                    "auth/device-session",
                    "auth/device-logout",
                    "auth/device-player-card",
                    "auth/device-erase",
                    "party/client/state",
                    "party/client/look",
                    "party/client/stop-looking",
                    "party/client/reserve",
                    "party/client/leave",
                    "party/client/publish",
                    "party/client/edit",
                    "party/client/pause",
                    "party/client/unlist",
                    "party/client/remove",
                    "party/client/chat",
                    "party/client/chat/report",
                    "party/client/listings?floor=F7",
                    "party/client/listings?floor=M7",
                ) || path.matches(Regex("party/client/listings/[A-Za-z0-9_-]{12}"))
        require(proof || allowed)
        require(proof == (token == null))
        if (token != null) require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    private fun request(
        path: String,
        body: JsonObject?,
        token: String?,
        limit: Int = 16384,
        timeout: Long = 12,
    ): String {
        validateBody(path, body)
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .use { client ->
                val request =
                    HttpRequest.newBuilder(
                            URI(
                                origin(
                                    FabricLoader.getInstance().isDevelopmentEnvironment,
                                    System.getProperty("mithrilpf.testApi"),
                                ) + path
                            )
                        )
                        .timeout(Duration.ofSeconds(timeout))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .apply { if (token != null) header("Authorization", "Bearer $token") }
                        .apply {
                            if (body == null) GET()
                            else POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        }
                        .build()
                val pending = client.sendAsync(request) { BoundedBody(limit) }
                val response =
                    try {
                        pending.get(timeout, TimeUnit.SECONDS)
                    } finally {
                        pending.cancel(true)
                        client.shutdownNow()
                    }
                if (response.statusCode() != 200)
                    throw ServiceFailure(
                        response.statusCode(),
                        failureReason(response.body()),
                        detail(response.body()),
                    )
                return response.body()
            }
    }

    internal fun validateBody(path: String, body: JsonObject?) {
        val maximum = if (path == "records/solo-progress") 640 * 1024 else 4096
        require(body == null || body.toString().toByteArray(Charsets.UTF_8).size <= maximum)
    }

    internal fun failureReason(body: String): String? = runCatching {
        val detail = JsonParser.parseString(body).asJsonObject.get("detail")
        val value =
            if (detail.isJsonObject) detail.asJsonObject.get("code").asString else detail.asString
        when (value) {
            "Chat muted" -> "muted"
            "Account or connection banned",
            "banned" -> "banned"
            "slot_taken",
            "party_full",
            "not_found" -> "slot_taken"
            "not_eligible" -> "not_eligible"
            "stats_unavailable" -> "stats_unavailable"
            "not_leader",
            "not_in_party",
            "in_party" -> "changed"
            "unknown_names" -> "unknown_names"
            else -> null
        }
    }
        .getOrNull()

    /** The server's own explanation, when it sent one as plain text. */
    internal fun detail(body: String): String? = runCatching {
        JsonParser.parseString(body).asJsonObject.get("detail").asString.take(200)
    }
        .getOrNull()

    /** Bounds allocation as data arrives, rather than after receiving the body. */
    private class BoundedBody(private val limit: Int) : HttpResponse.BodySubscriber<String> {
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
                if (item.remaining() > limit - size) {
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

class ServiceFailure(
    val statusCode: Int,
    val reason: String? = null,
    val detail: String? = null,
) : Exception("Mithril service unavailable")
