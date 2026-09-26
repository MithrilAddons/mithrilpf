package dev.mithril.mithrilpf.soloroom

import dev.mithril.mithrilpf.dungeontimer.SplitTime
import dev.mithril.mithrilpf.dungeontimer.TimerStamp

/** One dungeon world/run. Client-thread owned; a multiplayer run never becomes a solo run. */
class SoloRoomState(private val self: String) {
    private val participants = mutableSetOf<String>()
    private var started = false
    private var invalid = false

    private data class Visit(
        var clear: Boolean,
        var secrets: Boolean,
        var nanos: Long = 0,
        var ticks: Long = 0,
    )

    private val visits = mutableMapOf<String, Visit>()
    private var active: String? = null
    private var activeSince = TimerStamp(0, 0)
    val eligible
        get() = started && !invalid && participants == setOf(self)

    fun roster(names: Collection<String>) {
        participants.addAll(names)
        if (participants.any { it != self }) invalidate()
    }

    fun start() {
        started = true
    }

    fun invalidate() {
        invalid = true
        visits.clear()
        active = null
    }

    fun enter(room: String, clear: Boolean, secrets: Boolean, now: TimerStamp) {
        if (!eligible || active == room) return
        leave(now)
        visits.putIfAbsent(room, Visit(clear, secrets))
        active = room
        activeSince = now
    }

    /** Stop charging the previous room, including travel through untracked/unknown areas. */
    fun leave(now: TimerStamp) {
        active?.let { room ->
            visits[room]?.let { visit ->
                visit.nanos += (now.nanos - activeSince.nanos).coerceAtLeast(0)
                visit.ticks += (now.ticks - activeSince.ticks).coerceAtLeast(0)
            }
        }
        active = null
    }

    fun observe(
        room: String,
        clear: Boolean,
        secrets: Boolean,
        now: TimerStamp,
    ): Map<String, SplitTime> {
        if (!eligible) return emptyMap()
        val visit = visits[room] ?: return emptyMap()
        val nanos = if (active == room) (now.nanos - activeSince.nanos).coerceAtLeast(0) else 0
        val ticks = if (active == room) (now.ticks - activeSince.ticks).coerceAtLeast(0) else 0
        // Round only after summing visits, not once per observation or room transition.
        val time = SplitTime((visit.nanos + nanos) / 1_000_000, visit.ticks + ticks)
        val result = linkedMapOf<String, SplitTime>()
        if (clear && !visit.clear) {
            visit.clear = true
            result["Cleared"] = time
        }
        if (secrets && !visit.secrets) {
            visit.secrets = true
            result["Secrets"] = time
        }
        return result.filterValues { it.realMillis > 0 && it.ticks > 0 }
    }

    companion object {
        // Matches Hypixel's dungeon participant lines, including ghosts, not ordinary player lists.
        private val participant =
            Regex(
                "^\\[\\d+] (?:\\[[^]]+] )*([A-Za-z0-9_]{1,16}) .*\\((?:Healer|Mage|Berserk|Archer|Tank|DEAD)(?: \\w+)?\\)$"
            )

        fun participant(text: String): String? =
            participant.matchEntire(text.trim())?.groupValues?.get(1)
    }
}
