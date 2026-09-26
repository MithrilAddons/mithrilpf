package dev.mithril.mithrilpf.dungeontimer

import java.util.Locale

/** Show one improved clock without labelling a slower real time as a new PB. */
internal data class SplitPbResult(val timeText: String, val tickOnly: Boolean) {
    companion object {
        fun of(time: SplitTime, previous: DungeonBest?): SplitPbResult? {
            val realImproved = previous == null || time.realMillis < previous.realMillis
            val tickImproved = previous == null || time.ticks < previous.ticks
            if (!realImproved && !tickImproved) return null
            val seconds = if (realImproved) time.realMillis / 1000.0 else time.ticks / 20.0
            return SplitPbResult(String.format(Locale.ROOT, "%.1fs", seconds), !realImproved)
        }
    }
}
