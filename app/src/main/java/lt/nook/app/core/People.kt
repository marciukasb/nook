package lt.nook.app.core

/** Display names: first names only, unless two people share a first name (then their full names). */
fun shortNames(full: Map<String, String>): Map<String, String> {
    val first = full.mapValues { (_, n) -> n.trim().split(Regex("\\s+")).first().ifEmpty { "?" } }
    val clash = first.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return full.mapValues { (id, n) -> if (first.getValue(id) in clash) n.trim().replace(Regex("\\s+"), " ") else first.getValue(id) }
}
