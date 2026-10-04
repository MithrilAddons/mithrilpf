package dev.mithril.mithrilpf.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.mithril.mithrilpf.dungeontimer.DungeonTimerState
import dev.mithril.mithrilpf.dungeontimer.SplitTime
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import dev.mithril.mithrilpf.soloclear.DungeonScore
import dev.mithril.mithrilpf.soloclear.SoloClearState
import dev.mithril.mithrilpf.soloroom.CapturedMap
import dev.mithril.mithrilpf.soloroom.ReplaySnapshot
import dev.mithril.mithrilpf.soloroom.SoloRoomState
import java.util.Locale
import java.util.UUID

/** Client-thread capture, independent of Minecraft so complete event timelines can be tested. */
class RecordCapture {
    private val events = ArrayDeque<RecordEvent>()
    private val roster = linkedSetOf<String>()
    private val participants = linkedSetOf<String>()
    private val identities = mutableMapOf<String, String>()
    private var start: TimerStamp? = null
    private var run = 0L
    private var startedMillis = 0L
    private var nextSample = 0L
    private var solo = false

    fun poll(): RecordEvent? = events.removeFirstOrNull()

    fun clearEvents() = events.clear()

    fun invalidate() {
        solo = false
    }

    fun reset() {
        start = null
        roster.clear()
        participants.clear()
        identities.clear()
        solo = false
    }

    fun observeRoster(players: Collection<String>) {
        roster.addAll(players)
    }

    fun observeTab(lines: Collection<String>, profiles: Collection<Pair<String, UUID>>) {
        participants.addAll(
            lines.mapNotNull(SoloRoomState::participant).map { it.lowercase(Locale.ROOT) }
        )
        // Dungeon display rows can have synthetic UUIDs. Only resolve names using real profiles.
        profiles.forEach { (name, id) ->
            val key = name.lowercase(Locale.ROOT)
            if (id.version() == 4 && key in participants)
                identities[key] = id.toString().replace("-", "")
        }
        roster.clear()
        // Never silently drop an unresolved teammate, including one who has already left.
        if (participants.all { it in identities })
            observeRoster(participants.map { identities.getValue(it) })
    }

    fun sample(
        stamp: TimerStamp,
        dead: Boolean,
        state: SoloClearState?,
        score: DungeonScore,
        timer: DungeonTimerState?,
        complete: Boolean = false,
        map: CapturedMap? = null,
    ) {
        if (!solo || (!complete && stamp.nanos < nextSample)) return
        progress(
            stamp,
            dead,
            state?.status,
            score.evidence(timer?.completed?.containsKey("Boss Entry") == true),
            complete,
            map?.data,
            map?.replay,
        )
    }

    fun begin(
        floor: String,
        stamp: TimerStamp,
        epochMillis: Long,
        eligible: Boolean,
        paul: Boolean,
    ) {
        run++
        start = stamp
        startedMillis = epochMillis
        solo = floor in setOf("F7", "M7") && eligible
        nextSample = stamp.nanos + 5_000_000_000L
        if (solo)
            enqueue(
                "solo-start",
                JsonObject().apply {
                    addProperty("floor", floor)
                    addProperty("elapsed_ms", 0)
                    addProperty("ticks", 0)
                    addProperty("paul", paul)
                },
                stamp,
            )
    }

    fun progress(
        stamp: TimerStamp,
        dead: Boolean,
        status: String?,
        evidence: JsonObject,
        complete: Boolean = false,
        map: JsonObject? = null,
        replay: ReplaySnapshot? = null,
    ) {
        if (!solo) return
        val elapsed = stamp - requireNotNull(start)
        if (!complete && stamp.nanos < nextSample) return
        nextSample = stamp.nanos + 5_000_000_000L
        enqueue(
            "solo-progress",
            JsonObject().apply {
                addProperty("elapsed_ms", elapsed.realMillis)
                addProperty("ticks", elapsed.ticks)
                add("roster", rosterJson())
                addProperty("dead", dead)
                addProperty("valid", status in setOf("tracking", "awaiting_roster", "completed"))
                add("evidence", evidence)
                addProperty("complete", complete)
                if (complete && map != null) add("map", map.deepCopy())
            },
            stamp,
            replay.takeIf { complete && map != null },
        )
        if (complete || status !in setOf("tracking", "awaiting_roster")) solo = false
    }

    fun terminal(floor: String, time: SplitTime?, stamp: TimerStamp) {
        if (time == null || floor !in setOf("F7", "M7") || start == null || roster.isEmpty()) return
        enqueue(
            "terminal-report",
            JsonObject().apply {
                addProperty("report_id", UUID.randomUUID().toString().replace("-", ""))
                addProperty("floor", floor)
                addProperty("run_started_ms", startedMillis)
                add("roster", rosterJson())
                addProperty("real_ms", time.realMillis)
                addProperty("ticks", time.ticks)
            },
            stamp,
        )
    }

    private fun rosterJson() = JsonArray().apply { roster.sorted().forEach(::add) }

    private fun enqueue(
        path: String,
        body: JsonObject,
        stamp: TimerStamp,
        replay: ReplaySnapshot? = null,
    ) {
        if (events.size >= 8) {
            events.clear()
            solo = false
            return
        }
        body.addProperty("version", 2)
        events.addLast(RecordEvent(run, path, body.toString(), stamp.nanos, replay))
    }
}
