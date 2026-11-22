package us.theworkmans.proseplow.core.engine

/**
 * Turns the byte pieces of successive tokens into text without ever emitting part of a character.
 *
 * A token's bytes can stop anywhere: an emoji is four bytes and a tokenizer is free to spread them
 * over four tokens. Decoding each piece on its own would produce replacement characters, so the
 * bytes of an unfinished character are kept until the rest arrives.
 *
 * Only bytes that can still become a valid character are kept. Bytes that cannot (a stray
 * continuation byte, an overlong form, a surrogate, a sequence cut short by a byte that does not
 * belong to it) become one U+FFFD each time, following the usual practice of replacing the
 * longest invalid prefix, and decoding carries on with the byte after it. A model that emits
 * garbage therefore shows garbage marks instead of stalling the stream.
 */
internal class Utf8Assembler {

    private var pending = ByteArray(0)

    /** Adds one token's bytes and returns the characters that are now complete, possibly none. */
    fun feed(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val data = if (pending.isEmpty()) bytes else pending + bytes
        val out = StringBuilder(data.size)
        var index = 0
        while (index < data.size) {
            val lead = data[index].toInt() and 0xFF
            if (lead < 0x80) {
                out.append(lead.toChar())
                index++
                continue
            }
            val length = sequenceLength(lead)
            if (length == 0) {
                out.append(REPLACEMENT)
                index++
                continue
            }
            // Check the continuation bytes that have arrived so far.
            var seen = 1
            var valid = true
            while (seen < length && index + seen < data.size) {
                if (!isContinuation(lead, seen, data[index + seen].toInt() and 0xFF)) {
                    valid = false
                    break
                }
                seen++
            }
            if (!valid) {
                // The bytes before the offending one are replaced; that byte is looked at afresh.
                out.append(REPLACEMENT)
                index += seen
                continue
            }
            if (seen < length) break // A valid start of a character: wait for the rest.
            out.appendCodePoint(codePoint(data, index, length))
            index += length
        }
        pending = if (index == data.size) EMPTY else data.copyOfRange(index, data.size)
        return out.toString()
    }

    /**
     * Ends the stream. The bytes of a character that never completed are dropped: a reply that
     * was cut off in the middle of a character simply ends before it.
     */
    fun finish() {
        pending = EMPTY
    }

    private fun sequenceLength(lead: Int): Int = when (lead) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 0 // A continuation byte, an overlong lead (C0, C1) or a value beyond Unicode.
    }

    /** Whether [byte] may be the [position]-th byte (1-based after the lead) of a sequence. */
    private fun isContinuation(lead: Int, position: Int, byte: Int): Boolean {
        if (position > 1) return byte in 0x80..0xBF
        // The second byte is narrower for some leads: that is what excludes overlong forms,
        // surrogates and code points above U+10FFFF.
        return when (lead) {
            0xE0 -> byte in 0xA0..0xBF
            0xED -> byte in 0x80..0x9F
            0xF0 -> byte in 0x90..0xBF
            0xF4 -> byte in 0x80..0x8F
            else -> byte in 0x80..0xBF
        }
    }

    private fun codePoint(data: ByteArray, start: Int, length: Int): Int {
        var value = (data[start].toInt() and 0xFF) and (0xFF shr (length + 1))
        for (offset in 1 until length) {
            value = (value shl 6) or (data[start + offset].toInt() and 0x3F)
        }
        return value
    }

    private companion object {
        const val REPLACEMENT = '\uFFFD'
        val EMPTY = ByteArray(0)
    }
}
