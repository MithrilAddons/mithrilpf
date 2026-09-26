package dev.mithril.mithrilpf.dungeontimer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DungeonSplit(val name: String, val start: String? = null, val end: String? = null)

data class TimerStamp(val ticks: Long, val nanos: Long) {
    operator fun minus(start: TimerStamp) =
        SplitTime(
            (nanos - start.nanos).coerceAtLeast(0) / 1_000_000,
            (ticks - start.ticks).coerceAtLeast(0),
        )
}

@Serializable data class SplitTime(val realMillis: Long, val ticks: Long)

/** Pure, event-driven state. Only observed start/end pairs can become records. */
class DungeonTimerState(val floor: String, private val definitions: List<DungeonSplit>) {
    private val starts = linkedMapOf<String, TimerStamp>()
    val completed = linkedMapOf<String, SplitTime>()
    /**
     * Run-relative completion offsets for history/remaining-time estimates, not split durations.
     */
    val completedAt = linkedMapOf<String, SplitTime>()
    var ended = false
        private set

    fun rows(now: TimerStamp): Map<String, SplitTime> = starts.mapValues { (name, start) ->
        completed[name] ?: (now - start)
    }

    fun current(now: TimerStamp): Pair<String, SplitTime>? =
        starts.entries
            .lastOrNull { it.key !in TOTALS && it.key !in completed }
            ?.let { it.key to (now - it.value) }

    fun enterBoss(now: TimerStamp): Map<String, SplitTime> {
        if (ended || "Boss Entry" in completed) return emptyMap()
        val before = completed.keys.toSet()
        finish("Portal", now)
        finish("Boss Entry", now)
        return completed.filterKeys { it !in before }
    }

    fun chat(message: String, now: TimerStamp): Map<String, SplitTime> {
        if (ended) return emptyMap()
        val before = completed.keys.toSet()
        when {
            message == START -> {
                start("Blood Open", now)
                start("Boss Entry", now)
                start("Total", now)
            }
            message.startsWith("[BOSS] The Watcher:") -> {
                finish("Blood Open", now)
                start("Watcher Clear", now)
                if (message == WATCHER_DONE) {
                    finish("Watcher Clear", now)
                    start("Portal", now)
                }
            }
        }
        for ((index, definition) in definitions.withIndex()) {
            val name = clean(definition.name)
            val previous = definitions.getOrNull(index - 1)
            val inheritedStart =
                definition.start == null &&
                    previous?.end != null &&
                    clean(previous.name) in completed &&
                    matches(previous.end, message)
            if (matches(definition.start, message) || inheritedStart) {
                if (index == 0) enterBoss(now)
                start(name, now)
            }
            if (
                matches(definition.end, message) ||
                    (definition.end == null && RUN_END.matches(message))
            ) {
                finish(name, now)
            }
        }
        if (RUN_END.matches(message)) {
            finish("Total", now)
            ended = true
            // A lost end marker must not leave a fake running timer after the run ends.
            starts.keys.retainAll(completed.keys)
        }
        return completed.filterKeys { it !in before }
    }

    private fun start(name: String, now: TimerStamp) {
        starts.putIfAbsent(name, now)
    }

    private fun finish(name: String, now: TimerStamp) {
        val start = starts[name] ?: return
        if (completed.putIfAbsent(name, now - start) == null) {
            starts["Total"]?.let { completedAt[name] = now - it }
        }
    }

    companion object {
        const val START = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
        const val WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass."
        private val RUN_END = Regex("""\s*☠ Defeated (.+) in 0?([\dhms ]+?)\s*(\(NEW RECORD!\))?""")
        private val TOTALS = setOf("Boss", "Boss Entry", "Total")
        private val FORMATTING = Regex("[§&][0-9a-fk-or]", RegexOption.IGNORE_CASE)
        private val FLOOR =
            Regex("The Catacombs\\s*\\(\\s*([FM][1-7]|E)\\s*\\)", RegexOption.IGNORE_CASE)

        fun clean(text: String): String = text.replace(FORMATTING, "")

        fun detectFloor(lines: List<String>): String? = lines.firstNotNullOfOrNull {
            val line = clean(it).replace(Regex("[\\p{Cf}]"), "").replace('\u00a0', ' ')
            if (line.contains("Queue", ignoreCase = true)) null
            else FLOOR.find(line)?.groupValues?.get(1)?.uppercase()
        }

        fun loadDefinitions(): Map<String, List<DungeonSplit>> =
            requireNotNull(
                    DungeonTimerState::class
                        .java
                        .getResourceAsStream("/assets/mithrilpf/data/dungeon-splits.json")
                )
                .bufferedReader()
                .use { Json.decodeFromString(it.readText()) }

        private fun matches(pattern: String?, text: String): Boolean =
            pattern != null &&
                (pattern == text || (pattern.startsWith("\\") && Regex(pattern).matches(text)))

        fun countdown(ticks: Long): Int = 19 - ticks.mod(20)

        fun countdownColor(value: Int): Int {
            val green = (value.coerceIn(0, 19) * 255 / 19)
            return (0xFF shl 24) or ((255 - green) shl 16) or (green shl 8)
        }
    }
}
