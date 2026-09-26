package dev.mithril.mithrilpf.soloroom

import java.util.Locale

/** Chat compares the displayed real time, before rounding, against its previous PB. */
internal data class SoloRoomResult(val timeText: String, val newPb: Boolean, val color: Int) {
    companion object {
        fun of(realMillis: Long, bestMillis: Long?): SoloRoomResult {
            val newPb = bestMillis == null || realMillis < bestMillis
            val color =
                when {
                    newPb || realMillis - bestMillis <= bestMillis / 10 -> 0x55FF55
                    realMillis - bestMillis <= bestMillis / 4 -> 0xFFFF55
                    else -> 0xFF5555
                }
            return SoloRoomResult(
                String.format(Locale.ROOT, "%.1fs", realMillis / 1000.0),
                newPb,
                color,
            )
        }
    }
}
