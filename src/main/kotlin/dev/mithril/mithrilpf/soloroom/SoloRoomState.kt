package dev.mithril.mithrilpf.soloroom

import dev.mithril.mithrilpf.dungeontimer.SplitTime
import dev.mithril.mithrilpf.dungeontimer.TimerStamp

enum class RoomStyle(val label: String) {
    MIDCLEAR("Midclear"),
    REGULAR("Regular"),
}

/** Secrets counted over [span]; [counted] excludes a regular room's secrets found before white. */
data class RoomSecrets(val found: Int, val total: Int, val counted: Int, val span: SplitTime) {
    /** Stored as time per secret so a lower value is better, like every other PB. */
    val perSecret
        get() = SplitTime(span.realMillis / counted, (span.ticks / counted).coerceAtLeast(1))

    val perMinute
        get() = counted * 60_000.0 / span.realMillis
}

/**
 * One chat line's worth of results. Regular and no-secret rooms report their clear at white and the
 * rest when you leave; a midclear reports everything when you leave. Coming back for more secrets
 * reports the room again, recounted. Null parts didn't count. [style] is null for rooms without
 * secrets.
 */
data class RoomResult(
    val room: String,
    val style: RoomStyle?,
    val clear: SplitTime? = null,
    val secrets: RoomSecrets? = null,
    val total: SplitTime? = null,
) {
    val empty
        get() = clear == null && secrets == null && total == null
}

/**
 * Per-room PB attempts for one run, solo or party. Client-thread owned. A clear counts only if no
 * teammate was in the room before it turned white; secrets count only if the counter started at 0
 * and no teammate was in the room when it went up. Times are time spent inside the room.
 */
class SoloRoomState(private val self: String) {
    private var started = false
    private var invalid = false

    /** Living teammates by name, excluding you. */
    var teammates: Set<String> = emptySet()
        private set

    private class Attempt(
        val secretless: Boolean,
        val completeOnEntry: Boolean,
        var shared: Boolean,
    ) {
        var nanos = 0L
        var ticks = 0L
        var first: Int? = null
        var found = 0
        var total = 0
        var last: SplitTime? = null
        var tainted = false
        var clear: SplitTime? = null
        var foundAtWhite = 0
        var style: RoomStyle? = null
        /** Secrets found when last reported; null until the first report. */
        var reported: Int? = null
    }

    private val attempts = mutableMapOf<String, Attempt>()
    private val seenByOthers = mutableSetOf<String>()
    private var present = emptySet<String>()
    private var active: String? = null
    private var activeSince = TimerStamp(0, 0)
    private var previous: String? = null

    val eligible
        get() = started && !invalid

    fun roster(lines: Collection<String>) {
        teammates =
            lines
                .filterNot { it.trimEnd().endsWith("(DEAD)") }
                .mapNotNull(::participant)
                .filterNot { it.equals(self, true) }
                .toSet()
    }

    fun start() {
        started = true
    }

    fun invalidate() {
        invalid = true
        attempts.clear()
        active = null
    }

    /**
     * One tick standing in [tile]. [rooms] are the identified rooms by tile, [color] each tile's
     * map marker colour (0 when unknown) and [teammateTiles] where living teammates stand.
     */
    fun tick(
        rooms: List<SoloRoomDefinition?>,
        tile: Int,
        color: (Int) -> Int,
        teammateTiles: Set<Int>,
        now: TimerStamp,
    ): List<RoomResult> {
        if (!eligible) return emptyList()
        others(teammateTiles.mapNotNull { rooms.getOrNull(it)?.name }.toSet())
        val results = mutableListOf<RoomResult>()
        val room = rooms.getOrNull(tile)
        val here = color(tile)
        if (room != null && here != 0)
            results +=
                enter(
                    room.name,
                    room.tracked && here != 18,
                    here == 34 || here == 30,
                    room.secrets == 0,
                    now,
                )
        else leave(now)
        rooms.forEachIndexed { index, definition ->
            val marker = color(index)
            if (definition?.tracked == true && (marker == 34 || marker == 30))
                results += observe(definition.name, marker == 30 && definition.secrets > 0, now)
        }
        return results
    }

    /** An action bar line read while standing in [room]. */
    fun counter(room: SoloRoomDefinition?, text: String, now: TimerStamp) {
        val (found, total) = RunMapCapture.secretCount(text) ?: return
        // A reading for a neighbouring room at a doorway would have the wrong total.
        if (room != null && total == room.secrets) secrets(room.name, found, total, now)
    }

    /** The rooms teammates are standing in this tick. */
    fun others(rooms: Set<String>) {
        present = rooms
        seenByOthers += rooms
        rooms.forEach { room -> attempts[room]?.takeIf { it.clear == null }?.shared = true }
    }

    /**
     * You are standing in [room]. Walking into a different room reports the one you came from if it
     * is white and has new secrets since its last report. Only [tracked] rooms get an attempt.
     */
    fun enter(
        room: String,
        tracked: Boolean,
        complete: Boolean,
        secretless: Boolean,
        now: TimerStamp,
    ): List<RoomResult> {
        if (!eligible || active == room) return emptyList()
        leave(now)
        val results = previous?.takeIf { it != room }?.let(::report).orEmpty()
        previous = room
        if (tracked) {
            attempts.getOrPut(room) { Attempt(secretless, complete, room in seenByOthers) }
            active = room
            activeSince = now
        }
        return results
    }

    /** Stop charging the current room, including travel through untracked or unknown areas. */
    fun leave(now: TimerStamp) {
        active?.let { room ->
            val attempt = attempts.getValue(room)
            attempt.nanos += (now.nanos - activeSince.nanos).coerceAtLeast(0)
            attempt.ticks += (now.ticks - activeSince.ticks).coerceAtLeast(0)
        }
        active = null
    }

    /** The secret counter read while standing in [room]. */
    fun secrets(room: String, found: Int, total: Int, now: TimerStamp) {
        if (!eligible || active != room) return
        val attempt = attempts[room] ?: return
        if (attempt.first == null) attempt.first = found
        attempt.total = total
        if (found > attempt.found) rise(room, attempt, found, now)
    }

    private fun rise(room: String, attempt: Attempt, found: Int, now: TimerStamp) {
        attempt.found = found
        attempt.last = clock(room, attempt, now)
        if (room in present) attempt.tainted = true
    }

    /** [room]'s map marker is white or, when [green], green. */
    fun observe(room: String, green: Boolean, now: TimerStamp): List<RoomResult> {
        if (!eligible) return emptyList()
        val attempt = attempts[room]?.takeUnless { it.completeOnEntry } ?: return emptyList()
        // The map can turn green before the action bar shows the last secret.
        if (green && attempt.found < attempt.total) rise(room, attempt, attempt.total, now)
        val results = mutableListOf<RoomResult>()
        if (attempt.clear == null) {
            attempt.clear = clock(room, attempt, now)
            attempt.foundAtWhite = attempt.found
            attempt.style = style(attempt)
            if (attempt.style != RoomStyle.MIDCLEAR) clearResult(room, attempt)?.let(results::add)
        }
        if (green || attempt.secretless || attempt.found >= attempt.total) results += report(room)
        return results
    }

    /** Boss entry: every white room is as done as it will get. */
    fun finishAll(): List<RoomResult> = attempts.keys.toList().flatMap(::report)

    /**
     * The room's results counted over every visit so far. A report with nothing left that counts is
     * still returned after an earlier one, so that earlier one is withdrawn.
     */
    private fun report(room: String): List<RoomResult> {
        val attempt = attempts[room] ?: return emptyList()
        val white = attempt.clear ?: return emptyList()
        val before = attempt.reported
        if (before == attempt.found) return emptyList()
        attempt.reported = attempt.found
        val result = finishResult(room, attempt, white) ?: return emptyList()
        return listOfNotNull(result.takeIf { !it.empty || before != null })
    }

    // An unread counter, or one that didn't start at 0, means someone else may have been here.
    private fun style(attempt: Attempt) =
        when {
            attempt.secretless || attempt.first != 0 -> null
            attempt.found >= midclearThreshold(attempt.total) -> RoomStyle.MIDCLEAR
            else -> RoomStyle.REGULAR
        }

    private fun SplitTime.positive() = realMillis > 0 && ticks > 0

    private fun validClear(attempt: Attempt) =
        attempt.clear?.takeIf { !attempt.shared && it.positive() }

    private fun clearResult(room: String, attempt: Attempt): RoomResult? {
        if (!attempt.secretless && attempt.style == null) return null
        return validClear(attempt)?.let { RoomResult(room, attempt.style, clear = it) }
    }

    private fun finishResult(room: String, attempt: Attempt, white: SplitTime): RoomResult? {
        val style = attempt.style ?: return null
        val last = attempt.last
        val secrets =
            if (attempt.tainted || last == null) null
            else {
                val midclear = style == RoomStyle.MIDCLEAR
                val counted = if (midclear) attempt.found else attempt.found - attempt.foundAtWhite
                val span =
                    if (midclear) last
                    else SplitTime(last.realMillis - white.realMillis, last.ticks - white.ticks)
                if (counted < 1 || !span.positive()) null
                else RoomSecrets(attempt.found, attempt.total, counted, span)
            }
        val clear = validClear(attempt)
        val full =
            if (clear == null || secrets == null || secrets.found < secrets.total) null
            else
                SplitTime(
                    maxOf(clear.realMillis, last!!.realMillis),
                    maxOf(clear.ticks, last.ticks),
                )
        val shown = if (style == RoomStyle.MIDCLEAR) clear else null
        return RoomResult(room, style, shown, secrets, full)
    }

    // Round only after summing visits, not once per observation or room transition.
    private fun clock(room: String, attempt: Attempt, now: TimerStamp): SplitTime {
        val here = active == room
        val nanos =
            attempt.nanos + if (here) (now.nanos - activeSince.nanos).coerceAtLeast(0) else 0
        val ticks =
            attempt.ticks + if (here) (now.ticks - activeSince.ticks).coerceAtLeast(0) else 0
        return SplitTime(nanos / 1_000_000, ticks)
    }

    companion object {
        // Matches Hypixel's dungeon participant lines, including ghosts, not ordinary player lists.
        private val participant =
            Regex(
                "^\\[\\d+] (?:\\[[^]]+] )*([A-Za-z0-9_]{1,16}) .*\\((?:Healer|Mage|Berserk|Archer|Tank|DEAD)(?: \\w+)?\\)$"
            )

        fun participant(text: String): String? =
            participant.matchEntire(text.trim())?.groupValues?.get(1)

        /** Half the room's secrets rounded down, at least 1 and at most 3, found before white. */
        fun midclearThreshold(total: Int) = (total / 2).coerceIn(1, 3)
    }
}
