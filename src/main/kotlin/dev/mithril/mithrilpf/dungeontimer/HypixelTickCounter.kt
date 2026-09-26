package dev.mithril.mithrilpf.dungeontimer

/** Matches Noamm 1.2.6's normal clock: one tick per standalone, nonzero-ID ping. */
class HypixelTickCounter {
    var packets = 0L
        private set

    var ignoredBundled = 0L
        private set

    var ignoredZero = 0L
        private set

    fun accept(id: Int, bundled: Boolean = false): Boolean {
        packets++
        if (bundled) {
            ignoredBundled++
            return false
        }
        if (id == 0) {
            ignoredZero++
            return false
        }
        return true
    }

    fun reset() {
        packets = 0
        ignoredBundled = 0
        ignoredZero = 0
    }
}
