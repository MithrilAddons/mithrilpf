package dev.mithril.mithrilpf.games

import java.util.Locale

/** Autocomplete over the item list: the start of the name or of any word in it. */
object CuratorSearch {
    const val LIMIT = 6

    fun suggestions(
        items: List<CatalogItem>,
        query: String,
        guessed: Set<String>,
    ): List<CatalogItem> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return emptyList()
        return items
            .asSequence()
            .filter { it.id !in guessed }
            .mapNotNull { item ->
                val name = item.name.lowercase(Locale.ROOT)
                when {
                    name.startsWith(needle) -> 0 to item
                    name.split(' ', '-', '\'', '(').any { it.startsWith(needle) } -> 1 to item
                    else -> null
                }
            }
            .sortedWith(compareBy({ it.first }, { it.second.name.length }, { it.second.name }))
            .take(LIMIT)
            .map { it.second }
            .toList()
    }

    /** The item a typed name means, ignoring case; null unless the whole name matches. */
    fun exact(items: List<CatalogItem>, query: String): CatalogItem? = items.firstOrNull {
        it.name.equals(query.trim(), ignoreCase = true)
    }
}
