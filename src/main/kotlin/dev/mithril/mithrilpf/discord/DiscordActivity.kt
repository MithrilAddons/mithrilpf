package dev.mithril.mithrilpf.discord

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.mithril.mithrilpf.dungeontimer.DungeonTimerState
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import dev.mithril.mithrilpf.party.FinderActivity

data class DiscordActivity(val details: String, val state: String, val start: Long? = null) {
    fun json() =
        JsonObject().apply {
            addProperty("type", 0)
            addProperty("details", details.take(128))
            addProperty("state", state.take(128))
            if (start != null) add("timestamps", JsonObject().apply { addProperty("start", start) })
            add(
                "buttons",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("label", "Open Party Finder")
                            addProperty("url", "https://mithril.foo/party-finder")
                        }
                    )
                },
            )
        }
}

/** Client-thread-only model. Anchor each run once so wall-clock corrections cannot reset it. */
class DiscordActivityModel {
    private var run: DungeonTimerState? = null
    private var start: Long? = null

    fun activity(
        enabled: Boolean,
        inDungeon: Boolean,
        floor: String?,
        timer: DungeonTimerState?,
        stamp: TimerStamp,
        epochMillis: Long,
        finder: FinderActivity?,
    ): DiscordActivity? {
        if (run !== timer || !inDungeon) {
            run = if (inDungeon) timer else null
            start = null
        }
        if (!enabled) return null
        if (inDungeon) {
            val elapsed = timer?.rows(stamp)?.get("Total")?.realMillis
            if (start == null && elapsed != null) start = (epochMillis - elapsed) / 1000
            val phase =
                when {
                    timer?.ended == true -> "Run complete"
                    timer == null || elapsed == null -> "Preparing"
                    else ->
                        when (val name = timer.current(stamp)?.first) {
                            "Blood Open" -> "Blood Rush"
                            "Watcher Clear" -> "Blood Camp"
                            "Dragons" -> "Wither King"
                            null -> "Boss"
                            else -> name
                        }
                }
            val state =
                if (timer?.ended == true && elapsed != null) {
                    val seconds = elapsed / 1000
                    "Finished in ${seconds / 60}m ${seconds % 60}s"
                } else "In a dungeon"
            return DiscordActivity(
                "${floor ?: timer?.floor ?: "Dungeon"} · $phase",
                state,
                if (timer?.ended == true) null else start,
            )
        }
        return finder?.let {
            if (it.leader == null)
                DiscordActivity("${it.floor} · Looking for a party", "Party Finder")
            else DiscordActivity("${it.floor} · Party ${it.members}/5", "${it.leader}'s party")
        }
    }
}
