package dev.mithril.mithrilpf.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.mithril.mithrilpf.dungeontimer.DungeonBest

data class SyncedTiming(val floor: String, val kind: String, val realMillis: Long, val ticks: Long)

/** Only account-wide 300-score solo clears and the terminal phase; never room/run history. */
data class RecordSnapshot(val records: List<SyncedTiming>) {
    fun json() =
        JsonObject().apply {
            addProperty("version", 1)
            add(
                "records",
                JsonArray().apply {
                    records.forEach { record ->
                        add(
                            JsonObject().apply {
                                addProperty("floor", record.floor)
                                addProperty("kind", record.kind)
                                addProperty("real_ms", record.realMillis)
                                addProperty("ticks", record.ticks)
                            }
                        )
                    }
                },
            )
        }

    companion object {
        fun from(
            saved: Map<String, Map<String, Map<String, Map<String, DungeonBest>>>>,
            player: String,
        ): RecordSnapshot {
            val records = mutableListOf<SyncedTiming>()
            for (floor in listOf("F7", "M7")) {
                for ((store, split, kind) in
                    listOf(
                        Triple("solo", "300 Score", "solo_clear"),
                        Triple("splits", "Terminals", "terminals"),
                    )) {
                    val best = saved[store]?.get(player)?.get(floor)?.get(split) ?: continue
                    if (best.realMillis !in 1..7_200_000 || best.ticks !in 1..144_000) continue
                    records += SyncedTiming(floor, kind, best.realMillis, best.ticks)
                }
            }
            return RecordSnapshot(records)
        }
    }
}
