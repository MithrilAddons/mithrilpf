package dev.mithril.mithrilpf.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.mithril.mithrilpf.dungeontimer.DungeonTimerState
import dev.mithril.mithrilpf.dungeontimer.SplitTime
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import dev.mithril.mithrilpf.soloclear.DungeonScore
import dev.mithril.mithrilpf.soloclear.SoloClearState
import dev.mithril.mithrilpf.soloroom.SoloRoomState
import java.util.UUID

/** Client-thread capture, independent of Minecraft so complete event timelines can be tested. */
class RecordCapture {
    private val events = ArrayDeque<RecordEvent>()
    private val roster = linkedSetOf<String>()
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
        solo = false
    }

    fun observeRoster(players: Collection<String>) {
        roster.addAll(players)
    }

    fun observeTab(tab: Map<UUID, String>) {
        observeRoster(
            tab.filterValues { SoloRoomState.participant(it) != null }
                .keys
                .map { it.toString().replace("-", "") }
        )
    }

    fun sample(
        stamp: TimerStamp,
        dead: Boolean,
        state: SoloClearState?,
        score: DungeonScore,
        timer: DungeonTimerState?,
        complete: Boolean = false,
        map: JsonObject? = null,
    ) {
        if (!solo || (!complete && stamp.nanos < nextSample)) return
        progress(
            stamp,
            dead,
            state?.status,
            score.evidence(timer?.completed?.containsKey("Boss Entry") == true),
            complete,
            map,
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

    private fun enqueue(path: String, body: JsonObject, stamp: TimerStamp) {
        if (events.size >= 8) {
            events.clear()
            solo = false
            return
        }
        body.addProperty("version", 2)
        events.addLast(RecordEvent(run, path, body.toString(), stamp.nanos))
    }
}
