package dev.mithril.mithrilpf.soloclear

import kotlin.math.floor

/**
 * Local projected F7/M7 score, including unfinished blood/boss as in the old calculator. Adapted
 * from Noamm (CC0); no player chat claims, remote code or optional mod state. Missing mandatory
 * fields mean unknown, never a guessed 300.
 */
class DungeonScore {
    private var completed: Int? = null
    private var cleared: Int? = null
    private var secrets: Double? = null
    private var crypts: Int? = null
    private var puzzles: Int? = null
    private var solved = 0
    private var bloodIncluded = false
    private var watcherDone = false
    private var prince = false
    private var bat = false
    var mimic = false
    var deaths: Int? = null
        private set

    /** Only Mort's fresh-run message establishes zero deaths without a tab observation. */
    fun start() {
        deaths = 0
    }

    fun tab(lines: Collection<String>) {
        for (raw in lines) {
            val line = raw.trim()
            integer(COMPLETED, line)?.let { if (it in 0..100) completed = it }
            SECRETS.matchEntire(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                if (it.isFinite() && it in 0.0..100.0) secrets = it
            }
            integer(CRYPTS, line)?.let { if (it in 0..100) crypts = it }
            integer(DEATHS, line)?.let { if (it in 0..100) deaths = maxOf(deaths ?: 0, it) }
            integer(PUZZLES, line)?.let { if (it in 0..10) puzzles = it }
        }
        // The caller provides the complete retained raw tab, keyed by entry UUID.
        solved = lines.count { SOLVED.matches(it.trim()) }
    }

    fun sidebar(lines: Collection<String>) {
        for (line in lines) integer(CLEARED, line.trim())?.let {
            if (it in 0..100) {
                if (watcherDone && cleared != null && cleared != it) bloodIncluded = true
                cleared = it
            }
        }
    }

    fun chat(text: String) {
        when (text.trim().lowercase()) {
            "[boss] the watcher: you have proven yourself. you may pass." -> watcherDone = true
            "a prince falls. +1 bonus score" -> prince = true
            "a bat has been slain. +1 bonus score" -> bat = true
        }
        if (MIMIC.matches(text.trim())) mimic = true
    }

    fun estimate(elapsedSeconds: Long, inBoss: Boolean, paul: Boolean): Int? {
        val count = completed ?: return null
        val percent = cleared?.takeIf { it > 0 } ?: return null
        val secretPercent = secrets ?: return null
        val cryptCount = crypts ?: return null
        val puzzleCount = puzzles ?: return null
        val deathCount = deaths ?: return null
        val total = floor(count / (percent / 100.0) + 0.4).toInt()
        if (total <= 0 || total < count || solved > puzzleCount) return null
        val effective =
            (count + (if (bloodIncluded) 0 else 1) + (if (inBoss) 0 else 1)).coerceAtMost(total)
        val ratio = effective.toDouble() / total
        val roomScore = (ratio * 60).toInt()
        val skill =
            (20 + floor(ratio * 80).toInt() -
                    (puzzleCount - solved) * 10 -
                    (deathCount * 2 - 1).coerceAtLeast(0))
                .coerceIn(20, 100)
        val bonus =
            cryptCount.coerceAtMost(5) +
                (if (mimic) 2 else 0) +
                (if (prince) 1 else 0) +
                (if (bat) 1 else 0) +
                (if (paul) 10 else 0)
        return roomScore +
            skill +
            floor(secretPercent * 0.4).toInt().coerceIn(0, 40) +
            bonus +
            speed(elapsedSeconds)
    }

    private fun integer(pattern: Regex, text: String) =
        pattern.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun speed(seconds: Long): Int {
        var over = ((seconds - 840).coerceAtLeast(0) * 100.0 / 840)
        var deduction = 0.0
        for ((cap, divisor) in SPEED_PENALTIES) {
            val used = over.coerceAtMost(cap)
            deduction += used / divisor
            over -= used
        }
        return (100 - deduction - over / 6).toInt().coerceAtLeast(0)
    }

    companion object {
        private val COMPLETED = Regex("Completed Rooms: (\\d+)")
        private val SECRETS = Regex("Secrets Found: ([\\d.]+)%")
        private val CRYPTS = Regex("Crypts: (\\d+)")
        private val DEATHS = Regex("Deaths: \\(?([0-9]+)\\)?")
        private val PUZZLES = Regex("Puzzles: \\((\\d+)\\)")
        private val SOLVED = Regex(".+: \\[✔.*")
        private val CLEARED = Regex("Cleared: (\\d+)% \\(\\d+\\)")
        private val MIMIC =
            Regex(
                "charm you charmed a mimic and (captured \\d+ shards from it\\.|captured its shard\\.)",
                RegexOption.IGNORE_CASE,
            )
        private val SPEED_PENALTIES = listOf(20.0 to 2.0, 20.0 to 3.5, 10.0 to 4.0, 10.0 to 5.0)
    }
}
