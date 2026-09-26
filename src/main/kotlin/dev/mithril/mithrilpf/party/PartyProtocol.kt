package dev.mithril.mithrilpf.party

import com.google.gson.JsonObject
import com.google.gson.JsonParser

private val NAME = Regex("[A-Za-z0-9_]{1,16}")
private val ID = Regex("[A-Za-z0-9_-]{12}")

data class PartyMember(val name: String, val online: Boolean, val accepted: Boolean)

data class PartyHandoff(
    val id: String,
    val generation: String,
    val leader: String,
    val youLead: Boolean,
    val full: Boolean,
    val invited: Boolean,
    val members: List<PartyMember>,
) {
    val ready: Boolean
        get() = youLead && full && members.all { it.online }

    fun accepts(roster: GameRoster): Boolean =
        roster.leader.equals(leader, true) &&
            roster.names.all { name -> members.any { it.name.equals(name, true) } }
}

data class GameRoster(val leader: String, val names: List<String>)

data class PartyReport(
    val party: PartyHandoff,
    val roster: GameRoster,
    val retry: Boolean? = null,
) {
    fun json() =
        JsonObject().apply {
            addProperty("version", 1)
            addProperty("party_id", party.id)
            addProperty("handoff_id", party.generation)
            addProperty("leader", roster.leader)
            add("members", com.google.gson.JsonArray().apply { roster.names.forEach { add(it) } })
            if (retry != null) addProperty("retry", retry)
        }
}

data class PartyReply(
    val party: PartyHandoff?,
    val interval: Int = 25,
    val invites: List<String> = emptyList(),
)

object PartyProtocol {
    fun parse(text: String): JsonObject {
        require(text.length <= 16384)
        return JsonParser.parseString(text).asJsonObject.also {
            require(it.get("version")?.toString() == "1")
        }
    }

    fun reply(body: JsonObject): PartyReply {
        val value = body.get("party") ?: error("Missing party state")
        val party =
            if (value.isJsonNull) null
            else
                value.asJsonObject.let { p ->
                    val members =
                        p.getAsJsonArray("members").map { element ->
                            val member = element.asJsonObject
                            PartyMember(
                                name(member.get("name").asString),
                                boolean(member, "online"),
                                boolean(member, "accepted"),
                            )
                        }
                    require(
                        members.size in 1..5 &&
                            members.map { it.name.lowercase() }.distinct().size == members.size
                    )
                    val full = boolean(p, "full")
                    require(full == (members.size == 5))
                    val leader = name(p.get("leader").asString)
                    require(members.any { it.name.equals(leader, true) })
                    PartyHandoff(
                        p.get("party_id").asString.also { require(ID.matches(it)) },
                        p.get("handoff_id").asString.also { require(ID.matches(it)) },
                        leader,
                        boolean(p, "you_lead"),
                        full,
                        boolean(p, "invited"),
                        members,
                    )
                }
        val interval = body.get("interval_seconds")?.asInt ?: 25
        require(interval in 5..120)
        val invites = body.getAsJsonArray("invite")?.map { name(it.asString) } ?: emptyList()
        require(invites.size <= 4 && invites.distinct().size == invites.size)
        require(
            invites.isEmpty() ||
                party != null &&
                    party.youLead &&
                    party.invited &&
                    invites.all { invite ->
                        party.members.any { it.name == invite && !it.accepted } &&
                            !invite.equals(party.leader, true)
                    }
        )
        return PartyReply(party, interval, invites)
    }

    private fun name(value: String) = value.also { require(NAME.matches(it)) }

    private fun boolean(body: JsonObject, key: String): Boolean {
        val value = body.getAsJsonPrimitive(key)
        require(value.isBoolean)
        return value.asBoolean
    }
}
