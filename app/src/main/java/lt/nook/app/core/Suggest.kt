package lt.nook.app.core

/** An item name Nook has seen, with its last known section (category/shop). */
data class Known(val name: String, val section: String?, val uses: Int, val lastUsed: Long)

/**
 * Suggestions for what the user is typing: names starting with the query first, then names with a word starting
 * with it, then names containing it. Within each group: most used, then most recently used.
 */
fun suggest(query: String, known: List<Known>, limit: Int = 6): List<Known> {
    val q = nameKey(query)
    if (q.isEmpty()) return emptyList()
    return known.mapNotNull { k ->
        val key = nameKey(k.name)
        val rank = when {
            key.startsWith(q) -> 0
            key.split(' ').any { it.startsWith(q) } -> 1
            q in key -> 2
            else -> return@mapNotNull null
        }
        rank to k
    }.sortedWith(compareBy<Pair<Int, Known>> { it.first }
        .thenByDescending { it.second.uses }
        .thenByDescending { it.second.lastUsed })
        .take(limit)
        .map { it.second }
}
