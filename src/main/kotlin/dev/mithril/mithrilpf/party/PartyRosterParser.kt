package dev.mithril.mithrilpf.party

/** Only accepts a complete response to our /party list request, never public/player chat. */
class PartyRosterParser {
    private var deadline = 0L
    private var expected = 0
    private var leader: String? = null
    private val names = linkedMapOf<String, String>()

    fun request(now: Long) {
        reset()
        deadline = now + 5_000
    }

    fun reset() {
        deadline = 0
        expected = 0
        leader = null
        names.clear()
    }

    fun receive(raw: String, self: String, now: Long): GameRoster? {
        if (deadline == 0L || now > deadline) {
            reset()
            return null
        }
        val text = raw.replace(Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE), "").trim()
        if (text == "You are not currently in a party.") {
            reset()
            return GameRoster(self, listOf(self))
        }
        Regex("Party Members \\(([1-5])\\)").matchEntire(text)?.let {
            expected = it.groupValues[1].toInt()
            names.clear()
            leader = null
            return null
        }
        if (expected == 0) return null
        val row = Regex("Party (Leader|Moderators|Members): (.+)").matchEntire(text) ?: return null
        val content =
            row.groupValues[2].replace(Regex("\\[[^]\\r\\n]+]\\s*"), "").replace("●", "").trim()
        val entries = content.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (entries.any { !Regex("[A-Za-z0-9_]{1,16}").matches(it) } || entries.isEmpty()) {
            reset()
            return null
        }
        if (row.groupValues[1] == "Leader") {
            if (entries.size != 1) {
                reset()
                return null
            }
            leader = entries.single()
        }
        entries.forEach { names[it.lowercase()] = it }
        if (names.size > expected) {
            reset()
            return null
        }
        val currentLeader = leader ?: return null
        if (names.size != expected || self.lowercase() !in names) return null
        val result = GameRoster(currentLeader, names.values.toList())
        reset()
        return result
    }

    /**
     * Applies Hypixel's own join/leave lines to a confirmed roster. Anchored, so player chat
     * ("Name: …") never matches. Returns null for unrelated lines; an ended party clears the
     * roster.
     */
    fun membership(raw: String, roster: GameRoster): MembershipChange? {
        val text = raw.replace(Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE), "").trim()
        val rank = "(?:\\[[^]\\r\\n]+] )?"
        Regex("$rank([A-Za-z0-9_]{1,16}) joined the party\\.").matchEntire(text)?.let { match ->
            val name = match.groupValues[1]
            if (roster.names.any { it.equals(name, true) }) return null
            return MembershipChange(roster.copy(names = roster.names + name))
        }
        Regex("$rank([A-Za-z0-9_]{1,16}) has (?:left|been removed from) the party\\.")
            .matchEntire(text)
            ?.let { match ->
                val name = match.groupValues[1]
                if (name.equals(roster.leader, true)) return MembershipChange(null)
                val names = roster.names.filterNot { it.equals(name, true) }
                return if (names.size == roster.names.size) null
                else MembershipChange(roster.copy(names = names))
            }
        if (
            text == "You left the party." ||
                text.startsWith("You have been kicked from the party") ||
                text.startsWith("The party was transferred to ") ||
                text.startsWith("The party was disbanded") ||
                Regex("$rank[A-Za-z0-9_]{1,16} has disbanded the party!").matches(text)
        )
            return MembershipChange(null)
        return null
    }
}

/** A roster after a join/leave line; null when the game party ended or changed hands. */
data class MembershipChange(val roster: GameRoster?)
