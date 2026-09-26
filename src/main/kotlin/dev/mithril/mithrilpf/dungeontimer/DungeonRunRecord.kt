package dev.mithril.mithrilpf.dungeontimer

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class DungeonRunRecord(
    val id: String,
    val player: String,
    val floor: String,
    val startedAt: Long,
    val splits: Map<String, SplitTime>,
    val checkpoints: Map<String, SplitTime>,
    val lagMillis: Long,
    val partySizeAtStart: Int = 5,
    val version: Int = 1,
) {
    fun validate() {
        require(version == 1 && partySizeAtStart == 5)
        UUID.fromString(id)
        UUID.fromString(player)
        require(Regex("E|[FM][1-7]").matches(floor) && startedAt > 0)
        val total = requireNotNull(splits["Total"])
        require(total.realMillis > 0 && total.ticks > 0 && total.ticks <= Long.MAX_VALUE / 50)
        val delay = total.realMillis - total.ticks * 50
        require(delay >= -1000 && lagMillis == delay.coerceAtLeast(0) && lagMillis < 20_000)
        require(checkpoints.keys == splits.keys && checkpoints["Total"] == total)
        for (time in splits.values + checkpoints.values) {
            require(time.realMillis in 0..total.realMillis && time.ticks in 0..total.ticks)
        }
    }
}

/** One observed start. Never promote a short/unknown starting roster after late joins. */
class DungeonRunCapture(
    private val player: String,
    private val floor: String,
    private val startedAt: Long,
    participants: Set<String>,
    self: String,
    private val requiredSplits: Set<String>,
) {
    private val eligible = participants.size == 5 && self in participants
    private var finished = false

    fun finish(timer: DungeonTimerState): DungeonRunRecord? {
        if (finished || !eligible || !timer.ended || timer.floor != floor) return null
        finished = true
        if (!timer.completed.keys.containsAll(requiredSplits)) return null
        val total = timer.completed["Total"] ?: return null
        val result =
            DungeonRunRecord(
                UUID.randomUUID().toString(),
                player,
                floor,
                startedAt,
                timer.completed.toMap(),
                timer.completedAt.toMap(),
                (total.realMillis - total.ticks * 50).coerceAtLeast(0),
            )
        return try {
            result.validate()
            result
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
