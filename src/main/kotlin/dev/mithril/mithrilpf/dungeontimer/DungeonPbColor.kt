package dev.mithril.mithrilpf.dungeontimer

/** Compare tick durations only; names are the stable split/PB keys, not translated labels. */
internal object DungeonPbColor {
    const val GREEN: Int = 0xFF55FF55.toInt()
    const val YELLOW: Int = 0xFFFFFF55.toInt()
    const val RED: Int = 0xFFFF5555.toInt()

    fun color(split: String, ticks: Long, bestTicks: Long?): Int {
        if (bestTicks == null) return RED
        val (greenSeconds, yellowSeconds) =
            when (split) {
                "Blood Open" -> 5 to 10
                "Watcher Clear" -> 4 to 8
                "Portal" -> 1 to 1
                "Maxor",
                "Storm",
                "Necron" -> 1 to 2
                "Terminals" -> 3 to 7
                "Goldor" -> 1 to 3
                "Dragons" -> 2 to 4
                "Boss Entry",
                "Boss" -> 7 to 15
                "Total" -> 12 to 25
                else -> 1 to 3
            }
        val behindTicks = ticks - bestTicks
        return when {
            behindTicks <= greenSeconds * 20 -> GREEN
            behindTicks <= yellowSeconds * 20 -> YELLOW
            else -> RED
        }
    }
}
