package us.theworkmans.proseplow.core.engine

/**
 * Finds a stop string in text that arrives in arbitrary pieces, and decides what may be shown in
 * the meantime.
 *
 * Text is passed through at once, except for an ending that could still turn out to be the
 * beginning of a stop string: the longest such ending is held back until more text settles the
 * question. Nothing else is delayed, and the stop string itself is never passed on, in whole or
 * in part.
 *
 * The result does not depend on how the text was cut into pieces. The stop string that completes
 * first in the text wins; when two complete at the same character, the one that began earlier
 * wins (its beginning was being held back already).
 */
internal class StopStringMatcher(stopStrings: List<String>) {

    private val stops: List<String> = stopStrings.filter { it.isNotEmpty() }.distinct()
    private val longest: Int = stops.maxOfOrNull { it.length } ?: 0
    private val held = StringBuilder()

    /** The stop string that was reached, or null so far. */
    var matched: String? = null
        private set

    /**
     * Adds [text] and returns what can be emitted now. Once a stop string has been reached this
     * returns the text in front of it one last time and nothing ever after.
     */
    fun feed(text: String): String {
        if (matched != null) return ""
        if (stops.isEmpty()) return text
        held.append(text)

        var matchStart = -1
        var matchEnd = -1
        for (stop in stops) {
            val start = held.indexOf(stop)
            if (start < 0) continue
            val end = start + stop.length
            if (matchEnd < 0 || end < matchEnd || (end == matchEnd && start < matchStart)) {
                matchStart = start
                matchEnd = end
                matched = stop
            }
        }
        if (matchStart >= 0) {
            val before = held.substring(0, matchStart)
            held.setLength(0)
            return before
        }

        val keep = possiblePrefixLength()
        val release = held.substring(0, held.length - keep)
        held.delete(0, held.length - keep)
        return release
    }

    /**
     * Ends the stream and returns the text that was still held back. It was only ever the start
     * of a stop string that never completed, so it belongs to the reply. Empty after a match.
     */
    fun finish(): String {
        val rest = if (matched == null) held.toString() else ""
        held.setLength(0)
        return rest
    }

    /** The length of the longest ending of the held text that some stop string starts with. */
    private fun possiblePrefixLength(): Int {
        val limit = minOf(longest - 1, held.length)
        for (length in limit downTo 1) {
            val start = held.length - length
            if (stops.any { stop -> stop.length > length && stop.regionMatches(0, held, start, length) }) return length
        }
        return 0
    }
}
