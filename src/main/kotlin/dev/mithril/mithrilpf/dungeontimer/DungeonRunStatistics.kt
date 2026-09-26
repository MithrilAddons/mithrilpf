package dev.mithril.mithrilpf.dungeontimer

/** Immutable summary published by the storage worker; no sorting or IO in the HUD. */
data class DungeonRunStatistics(
    val count: Int,
    val total: SplitTime,
    val remaining: Map<String, SplitTime>,
) {
    fun estimate(timer: DungeonTimerState, now: TimerStamp): SplitTime? {
        timer.completed["Total"]?.let {
            return it
        }
        val elapsed = timer.rows(now)["Total"] ?: return null
        // The latest observed endpoint already includes the actual times of all preceding phases.
        val checkpoint = timer.completedAt.entries.lastOrNull { it.key in remaining }
        val base = checkpoint?.let { add(it.value, remaining.getValue(it.key)) } ?: total
        // If the current phase overruns, do not let the estimate consume time needed AFTER it.
        val tail = timer.current(now)?.first?.let(remaining::get) ?: SplitTime(0, 0)
        val lowerBound = add(elapsed, tail)
        return SplitTime(
            maxOf(base.realMillis, lowerBound.realMillis),
            maxOf(base.ticks, lowerBound.ticks),
        )
    }

    companion object {
        // Defaults
        private val M7_FALLBACK: DungeonRunStatistics = run {
            val seconds =
                linkedMapOf(
                    "Blood Open" to 20L,
                    "Watcher Clear" to 64L,
                    "Portal" to 4L,
                    "Maxor" to 26L,
                    "Storm" to 46L,
                    "Terminals" to 40L,
                    "Goldor" to 8L,
                    "Necron" to 31L,
                    "Dragons" to 57L,
                )
            val total = seconds.values.sum()
            var elapsed = 0L
            val remaining =
                seconds
                    .mapValues { (_, duration) ->
                        elapsed += duration
                        SplitTime((total - elapsed) * 1000, (total - elapsed) * 20)
                    }
                    .toMutableMap()
            remaining["Boss Entry"] = remaining.getValue("Portal")
            remaining["Boss"] = SplitTime(0, 0)
            DungeonRunStatistics(0, SplitTime(total * 1000, total * 20), remaining.toMap())
        }

        fun fallback(floor: String): DungeonRunStatistics? =
            if (floor == "M7") M7_FALLBACK else null

        fun forEstimate(floor: String, saved: DungeonRunStatistics?): DungeonRunStatistics? =
            saved?.takeIf { it.count >= 3 } ?: fallback(floor)

        fun from(runs: List<DungeonRunRecord>): DungeonRunStatistics? {
            if (runs.isEmpty()) return null
            return DungeonRunStatistics(
                runs.size,
                median(runs.map { it.splits.getValue("Total") }),
                runs
                    .flatMap { it.checkpoints.keys }
                    .distinct()
                    .filter { it != "Total" }
                    .associateWith { name ->
                        median(
                            runs.mapNotNull { run ->
                                run.checkpoints[name]?.let { end ->
                                    val total = run.splits.getValue("Total")
                                    SplitTime(
                                        total.realMillis - end.realMillis,
                                        total.ticks - end.ticks,
                                    )
                                }
                            }
                        )
                    },
            )
        }

        private fun add(a: SplitTime, b: SplitTime) =
            SplitTime(a.realMillis + b.realMillis, a.ticks + b.ticks)

        private fun median(values: List<SplitTime>) =
            SplitTime(
                medianLong(values.map { it.realMillis }),
                medianLong(values.map { it.ticks }),
            )

        private fun medianLong(values: List<Long>): Long {
            val sorted = values.sorted()
            val high = sorted[sorted.size / 2]
            val low = sorted[(sorted.size - 1) / 2]
            return low + (high - low) / 2
        }
    }
}
