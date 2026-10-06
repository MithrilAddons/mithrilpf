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
}
