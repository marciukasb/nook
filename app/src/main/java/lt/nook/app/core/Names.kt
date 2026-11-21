package lt.nook.app.core

import java.text.Normalizer

private val marks = Regex("\\p{M}+")
private val spaces = Regex("\\s+")

/** Matching key for an item name: lowercased, trimmed, diacritics removed, whitespace collapsed. */
fun nameKey(text: String): String =
    Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD)
        .replace(marks, "")
        .replace(spaces, " ")
