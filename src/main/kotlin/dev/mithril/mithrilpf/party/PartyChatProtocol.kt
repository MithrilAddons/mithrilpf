package dev.mithril.mithrilpf.party

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** An in-memory scoped credential. Deliberately not a data class (no token in toString). */
class ChatAccess(val account: String, val partyId: String, val token: String) {
    init {
        require(Regex("[0-9a-f]{32}").matches(account))
        require(Regex("[A-Za-z0-9_-]{12}").matches(partyId))
        require(Regex("[A-Za-z0-9_-]{43}").matches(token))
    }

    fun same(other: ChatAccess?) =
        other != null &&
            account == other.account &&
            partyId == other.partyId &&
            token == other.token
}

data class PartyChatMessage(
    val id: Long,
    val uuid: String,
    val name: String,
    val text: String,
    val source: String,
)

data class PartyChatBatch(
    val partyId: String,
    val latest: Long,
    val messages: List<PartyChatMessage>,
)

object PartyChatProtocol {
    fun validText(text: String): Boolean =
        text.isNotBlank() &&
            text.codePointCount(0, text.length) in 1..256 &&
            text.codePoints().allMatch {
                !Character.isISOControl(it) &&
                    it !in 0xD800..0xDFFF &&
                    it != 0xA7 &&
                    it !in 0x202A..0x202E &&
                    it !in 0x2066..0x2069
            }

    fun read(partyId: String, after: Long) =
        JsonObject().apply {
            addProperty("version", 1)
            addProperty("party_id", partyId)
            addProperty("after", after)
        }

    fun send(partyId: String, text: String, requestId: String) =
        read(partyId, 0).apply {
            require(validText(text))
            require(Regex("[A-Za-z0-9_-]{16,64}").matches(requestId))
            remove("after")
            addProperty("text", text)
            addProperty("request_id", requestId)
        }

    private fun parse(text: String): JsonObject {
        require(text.toByteArray(Charsets.UTF_8).size <= 262144)
        return JsonParser.parseString(text).asJsonObject.also {
            require(it["version"].toString() == "1")
        }
    }

    private fun string(body: JsonObject, key: String): String {
        val value = body.getAsJsonPrimitive(key)
        require(value.isString)
        return value.asString
    }

    private fun message(body: JsonObject): PartyChatMessage {
        val id = string(body, "id")
        require(Regex("[1-9][0-9]{0,15}").matches(id))
        val sender = body.getAsJsonObject("sender")
        val uuid = string(sender, "uuid")
        val name = string(sender, "name")
        val text = string(body, "text")
        val source = string(body, "source")
        require(Regex("[0-9a-f]{32}").matches(uuid))
        require(Regex("[A-Za-z0-9_]{1,16}").matches(name))
        require(validText(text) && source in setOf("web", "game"))
        return PartyChatMessage(id.toLong(), uuid, name, text, source)
    }

    fun batch(text: String, partyId: String, after: Long): PartyChatBatch {
        val body = parse(text)
        require(string(body, "party_id") == partyId)
        val latestText = body["latest"].toString()
        require(Regex("0|[1-9][0-9]{0,15}").matches(latestText))
        val latest = latestText.toLong()
        require(latest >= after)
        val items = body.getAsJsonArray("messages")
        require(items.size() <= 100)
        val messages = items.map { message(it.asJsonObject) }
        require(messages.all { it.id > after && it.id <= latest })
        require(messages.zipWithNext().all { (a, b) -> a.id < b.id })
        require((latest == after && messages.isEmpty()) || messages.lastOrNull()?.id == latest)
        return PartyChatBatch(partyId, latest, messages)
    }

    fun acknowledgement(text: String, account: String, expected: String): PartyChatMessage =
        message(parse(text).getAsJsonObject("message")).also {
            require(it.uuid == account && it.text == expected && it.source == "game")
        }
}
