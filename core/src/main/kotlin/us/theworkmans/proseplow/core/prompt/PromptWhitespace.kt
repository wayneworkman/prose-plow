package us.theworkmans.proseplow.core.prompt

/**
 * Whitespace as the models' chat templates understand it.
 *
 * The templates trim message content with Jinja's `trim`, which is Python's `str.strip()`. That
 * removes exactly the characters below and no others. Kotlin's `trim()` uses a different set (it
 * keeps U+0085, for one), so every place that must agree with a template uses this object instead:
 * the prompt formats, the history rules and the engine's handling of the start of a reply.
 */
object PromptWhitespace {

    /** True for the characters Python's `str.strip()` removes. All of them are in the basic plane. */
    fun isWhitespace(char: Char): Boolean = when (char) {
        in '\u0009'..'\u000D', in '\u001C'..'\u001F', ' ', '\u0085', '\u00A0', '\u1680',
        in '\u2000'..'\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
        -> true
        else -> false
    }

    /** Python's `text.strip()`. */
    fun strip(text: String): String = stripEnd(stripStart(text))

    /** Python's `text.lstrip()`. */
    fun stripStart(text: String): String {
        var start = 0
        while (start < text.length && isWhitespace(text[start])) start++
        return text.substring(start)
    }

    /** Python's `text.rstrip()`. */
    fun stripEnd(text: String): String {
        var end = text.length
        while (end > 0 && isWhitespace(text[end - 1])) end--
        return text.substring(0, end)
    }

    /** True when [text] is empty or [strip] would leave nothing of it. */
    fun isBlank(text: String): Boolean = text.all(::isWhitespace)
}
