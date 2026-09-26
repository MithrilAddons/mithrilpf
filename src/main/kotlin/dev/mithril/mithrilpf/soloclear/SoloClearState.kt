package dev.mithril.mithrilpf.soloclear

import dev.mithril.mithrilpf.dungeontimer.SplitTime
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import dev.mithril.mithrilpf.soloroom.SoloRoomState

/** A solo attempt. Another participant permanently disqualifies this world as solo. */
class SoloClearState(private val self: String) {
    private val participants = mutableSetOf<String>()
    private var start: TimerStamp? = null
    private var last: TimerStamp? = null
    private var sawFreshScore = false
    var floor: String? = null
        private set

    var status = "waiting"
        private set

    var score: Int? = null
        private set

    val active
        get() = status == "tracking" || status == "awaiting_roster"

    val rosterSummary: String
        get() = participants.sorted().joinToString().ifEmpty { "none" }

    fun rosterLines(lines: Collection<String>) {
        roster(lines.mapNotNull(SoloRoomState::participant))
    }

    fun roster(names: Collection<String>) {
        participants.addAll(names.map { it.lowercase() })
        if (participants.any { !it.equals(self, true) }) invalidate("not_solo")
        if (status == "awaiting_roster" && participants.size == 1) status = "tracking"
    }

    fun begin(floor: String?, now: TimerStamp) {
        if (start != null || status != "waiting") return
        if (floor !in setOf("F7", "M7")) {
            invalidate("floor")
            return
        }
        this.floor = floor
        start = now
        last = now
        // Preserve Mort's timestamp even when tab packets arrive later.
        status = if (participants.isEmpty()) "awaiting_roster" else "tracking"
    }

    fun invalidate(reason: String) {
        if (status == "completed") return
        if (status == "waiting" || active) status = reason
    }

    fun observe(score: Int?, dead: Boolean, now: TimerStamp): SplitTime? {
        if (!active) return null
        if (dead) {
            invalidate("death")
            return null
        }
        val before = last ?: return null
        if (now.ticks < before.ticks || now.nanos < before.nanos) {
            invalidate("clock")
            return null
        }
        last = now
        val elapsed = now - requireNotNull(start)
        if (elapsed.realMillis > 7_200_000) {
            invalidate("expired")
            return null
        }
        if (score == null || score !in 0..400) return null
        this.score = score
        // Ignore a prior world's high score until this run's reset/progression was observed.
        if (score < 300) sawFreshScore = true
        if (!sawFreshScore || score < 300 || elapsed.ticks <= 0 || elapsed.realMillis <= 0)
            return null
        if (status == "awaiting_roster") {
            invalidate("roster")
            return null
        }
        status = "completed"
        return elapsed
    }
}
