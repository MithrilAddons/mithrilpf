package dev.mithril.mithrilpf.finder

/** Prime on first connection; report each subsequent notice once despite held-request retries. */
class FinderNotices {
    private var seen: Set<String>? = null

    fun accept(notices: List<FinderNotice>): List<FinderNotice> {
        val previous = seen
        seen = (previous.orEmpty() + notices.map { it.id }).toList().takeLast(100).toSet()
        return if (previous == null) emptyList() else notices.filter { it.id !in previous }
    }

    fun reset() {
        seen = null
    }
}
