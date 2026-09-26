package dev.mithril.mithrilpf.dungeontimer

/**
 * A missing sidebar line during a refresh is not a dungeon exit. Reset on world/location changes.
 */
class DungeonLocation {
    var floor: String? = null
        private set

    var apiDungeon: Boolean? = null
        private set

    var source: String = "none"
        private set

    val inDungeon: Boolean
        get() = apiDungeon ?: (floor != null)

    fun observe(lines: List<String>, source: String) {
        if (apiDungeon == false) return
        val detected = DungeonTimerState.detectFloor(lines) ?: return
        floor = detected
        this.source = source
    }

    fun location(isDungeon: Boolean) {
        apiDungeon = isDungeon
        if (!isDungeon) {
            floor = null
            source = "location API (outside dungeon)"
        }
    }

    fun reset() {
        floor = null
        apiDungeon = null
        source = "none"
    }

    companion object {
        fun isHypixel(brand: String?, address: String?, apiConfirmed: Boolean): Boolean {
            if (apiConfirmed || brand?.contains("hypixel", ignoreCase = true) == true) return true
            val host = address.orEmpty().substringBefore(':').trim().trimEnd('.').lowercase()
            return host == "hypixel.net" || host.endsWith(".hypixel.net")
        }
    }
}
