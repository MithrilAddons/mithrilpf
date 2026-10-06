package dev.mithril.mithrilpf.party

/**
 * Follows the player's Hypixel party from Hypixel's own English system lines, without commands.
 * Patterns are anchored, so player, party, guild and private chat ("Name: …") never match.
 */
class GamePartyTracker {
    /** Null until a launch or reconnect is followed by a line that states the whole party. */
    var roster: GameRoster? = null
        private set

    fun reset() {
        roster = null
    }

    fun set(value: GameRoster) {
        roster = value
    }

    /** Returns true when the line changed the known party. */
    fun receive(raw: String, self: String): Boolean {
        val text = raw.replace(Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE), "").trim()
        if (text in ENDED || ENDED_PREFIXES.any(text::startsWith) || DISBANDED.matches(text))
            return update(GameRoster(self, listOf(self)))
        JOINED_OTHER.matchEntire(text)?.let {
            val leader = it.groupValues[1]
            return update(GameRoster(leader, listOf(leader, self).distinctBy(String::lowercase)))
        }
        val current = roster ?: return false
        PARTYING_WITH.matchEntire(text)?.let { match ->
            val names = match.groupValues[1].split(", ").map { name(it) ?: return false }
            return update(
                current.copy(names = (current.names + names).distinctBy(String::lowercase))
            )
        }
        JOINED.matchEntire(text)?.let {
            return update(
                current.copy(
                    names = (current.names + it.groupValues[1]).distinctBy(String::lowercase)
                )
            )
        }
        LEFT.matchEntire(text)?.let {
            return update(current.copy(names = current.names.without(it.groupValues[1])))
        }
        TRANSFERRED.matchEntire(text)?.let {
            val names =
                if (it.groupValues[2] == "because") current.names.without(it.groupValues[3])
                else current.names
            return update(GameRoster(it.groupValues[1], names))
        }
        return false
    }

    private fun update(value: GameRoster): Boolean {
        if (value == roster) return false
        roster = value
        return true
    }

    private fun List<String>.without(name: String) = filterNot { it.equals(name, true) }

    private fun name(entry: String) = NAMED.matchEntire(entry.trim())?.groupValues?.get(1)

    private companion object {
        const val RANK = "(?:\\[[^]\\r\\n]+] )?"
        const val NAME = "([A-Za-z0-9_]{1,16})"
        val NAMED = Regex("$RANK$NAME")
        val ENDED =
            setOf(
                "You left the party.",
                "You are not in a party right now.",
                "You are not currently in a party.",
                "You are not in a party.",
            )
        val ENDED_PREFIXES =
            listOf("You have been kicked from the party by ", "The party was disbanded because ")
        val DISBANDED = Regex("$RANK$NAME has disbanded the party!")
        val JOINED_OTHER = Regex("You have joined $RANK$NAME's? party!")
        val PARTYING_WITH = Regex("You'll be partying with: (.+)")
        val JOINED = Regex("$RANK$NAME joined the party\\.")
        val LEFT =
            Regex(
                "$RANK$NAME (?:has left the party|has been removed from the party|" +
                    "was removed from (?:the|your) party because they disconnected)\\."
            )
        val TRANSFERRED =
            Regex("The party was transferred to $RANK$NAME (by|because) $RANK$NAME(?: left)?")
    }
}
