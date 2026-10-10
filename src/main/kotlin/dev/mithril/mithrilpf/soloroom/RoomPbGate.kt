package dev.mithril.mithrilpf.soloroom

import dev.mithril.mithrilpf.dungeontimer.DungeonBest
import dev.mithril.mithrilpf.dungeontimer.DungeonTimeFormat
import dev.mithril.mithrilpf.dungeontimer.SplitTime
import dev.mithril.mithrilpf.ui.Palette
import java.util.Locale
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * Room PB results for one run. Clears save straight away; secrets and Total wait until the run
 * passes the 300 gate, and are dropped if it doesn't. Client-thread owned.
 */
class RoomPbGate(private val host: Host) {
    /** Where results are saved and shown: the current player's floor. */
    interface Host {
        /** Saved room PBs for the current player and floor. */
        fun best(): Map<String, DungeonBest>

        /** Saves [times], then reports how many improved. */
        fun save(times: Map<String, SplitTime>, saved: (Int) -> Unit)

        fun send(line: Component)
    }

    private val pending = linkedMapOf<String, SplitTime>()
    private val pendingPbs = mutableSetOf<String>()

    /** Null until decided. */
    var passed: Boolean? = null
        private set

    fun results(results: List<RoomResult>) {
        val best = host.best()
        for (result in results) {
            val (save, lines) = accept(result, best)
            host.save(save) {}
            lines.forEach(host::send)
        }
    }

    /** Boss entry decides the gate on the run's [estimate] with deaths ignored. */
    fun bossEntry(rooms: SoloRoomState?, estimate: () -> Int?) =
        close(rooms) { estimate().let { (it != null && it >= 300) to it } }

    /** A solo run reaching 300 passes the gate. */
    fun soloPassed(rooms: SoloRoomState?) = close(rooms) { true to null }

    /**
     * Decides the gate once. Rooms left white are reported first so their results count, and room
     * tracking stops for the rest of the run.
     */
    private fun close(rooms: SoloRoomState?, outcome: () -> Pair<Boolean, Int?>) {
        if (passed != null) return
        rooms?.finishAll()?.let(::results)
        rooms?.invalidate()
        val (passed, estimate) = outcome()
        settle(passed, estimate)
    }

    private fun settle(passed: Boolean, estimate: Int?) {
        val (save, dropped) = decide(passed, estimate)
        dropped?.let(host::send)
        host.save(save) { if (it > 0) host.send(saved(it)) }
    }

    /** What to save now and the chat lines for one result. */
    data class Outcome(val save: Map<String, SplitTime>, val lines: List<Component>)

    /** [best] holds the player's saved room PBs for this floor. */
    fun accept(result: RoomResult, best: Map<String, DungeonBest>): Outcome {
        val suffix = result.style?.let { " · ${it.label}" }.orEmpty()
        val clearKey = "${result.room} · Clear$suffix"
        val secretsKey = "${result.room} · Secrets$suffix"
        val totalKey = "${result.room} · Total"
        val gated = buildMap {
            result.secrets?.let { put(secretsKey, it.perSecret) }
            result.total?.let { put(totalKey, it) }
        }
        // A room reported again replaces what it reported before, even with nothing left. Only a
        // regular or no-secret room's clear at white is reported on its own.
        if (result.style == RoomStyle.MIDCLEAR || result.clear == null) {
            pending -= setOf(secretsKey, totalKey)
            pendingPbs -= setOf(secretsKey, totalKey)
        }
        val waiting = gated.isNotEmpty() && passed == null
        if (waiting) pending += gated
        val save =
            result.clear?.let { mapOf(clearKey to it) }.orEmpty() +
                gated.takeIf { passed == true }.orEmpty()

        fun value(text: String, millis: Long, key: String): MutableComponent {
            val shown = SoloRoomResult.of(millis, best[key]?.realMillis)
            val part = Component.literal(text).withStyle { it.withColor(shown.color) }
            if (shown.newPb) {
                part.append(message("pb_suffix").withStyle(ChatFormatting.GOLD))
                if (waiting && key in gated) pendingPbs += key
            }
            return part
        }
        fun time(time: SplitTime, key: String) =
            value(SoloRoomResult.of(time.realMillis, null).timeText, time.realMillis, key)

        val secrets =
            result.secrets?.let {
                Component.literal("${it.found}/${it.total} ")
                    .withStyle(ChatFormatting.WHITE)
                    .append(value(rate(it.perMinute), it.perSecret.realMillis, secretsKey))
            }
        val total = result.total?.let { message("room_total").append(time(it, totalKey)) }
        val lines = mutableListOf<MutableComponent>()
        if (result.style == RoomStyle.MIDCLEAR && !result.empty) {
            lines +=
                joined(
                    message("room_midclear", result.room),
                    listOfNotNull(
                        result.clear?.let { message("room_time").append(time(it, clearKey)) },
                        secrets?.let { message("room_secrets_part").append(it) },
                        total,
                    ),
                )
        } else if (result.style != RoomStyle.MIDCLEAR) {
            result.clear?.let {
                lines += message("room_result", result.room, message("clear"), time(it, clearKey))
            }
            if (secrets != null || total != null)
                lines += joined(message("room_secrets", result.room), listOfNotNull(secrets, total))
        }
        if (waiting) lines.last().append(message("room_needs_300").withStyle(ChatFormatting.GRAY))
        return Outcome(save, lines.map(::accent))
    }

    /**
     * Decides the gate once. Returns what to save, and a line when results that would have been PBs
     * are dropped.
     */
    fun decide(passed: Boolean, estimate: Int?): Pair<Map<String, SplitTime>, Component?> {
        if (this.passed != null) return emptyMap<String, SplitTime>() to null
        this.passed = passed
        val waiting = pending.toMap()
        val missed = pendingPbs.isNotEmpty()
        clear()
        if (passed) return waiting to null
        val line =
            if (missed) accent(message("room_pbs_dropped", estimate?.toString() ?: "?")) else null
        return emptyMap<String, SplitTime>() to line
    }

    /** Drops anything waiting, without deciding the gate. */
    fun clear() {
        pending.clear()
        pendingPbs.clear()
    }

    companion object {
        private fun saved(count: Int): Component = accent(message("room_pbs_saved", count))

        /**
         * A room PB for the PB list: secrets per minute, and old clears from before styles were
         * tracked labelled until the room has a time for both styles.
         */
        fun listing(name: String, best: DungeonBest, splits: Map<String, DungeonBest>): String? {
            if (" · Secrets · " in name) return "$name: ${rate(60_000.0 / best.realMillis)}"
            val time = DungeonTimeFormat.pair(SplitTime(best.realMillis, best.ticks))
            if (!name.endsWith(" · Cleared")) return "$name: $time"
            val room = name.removeSuffix(" · Cleared")
            if (RoomStyle.entries.all { "$room · Clear · ${it.label}" in splits }) return null
            return "$room · Clear (${message("style_unknown").string}): $time"
        }

        private fun rate(perMinute: Double) = String.format(Locale.ROOT, "%.1f/min", perMinute)

        private fun message(key: String, vararg values: Any) =
            Component.translatable("tracking.mithrilpf.$key", *values)

        private fun accent(line: MutableComponent) = line.withStyle {
            it.withColor(Palette.ACCENT and 0xFFFFFF)
        }

        private fun joined(head: MutableComponent, parts: List<Component>): MutableComponent {
            parts.forEachIndexed { index, part ->
                if (index > 0) head.append(" · ")
                head.append(part)
            }
            return head
        }
    }
}
