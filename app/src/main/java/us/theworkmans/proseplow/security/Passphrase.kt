package us.theworkmans.proseplow.security

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Why a passphrase is not accepted (a new one, or one entered to unlock). */
enum class PassphraseProblem {
    EMPTY,
    TOO_SHORT,
    TOO_LONG,
    DIGITS_ONLY,

    /** A new passphrase was entered twice and the two entries differ. */
    MISMATCH,
}

/**
 * The app's own passphrase rules: at least [MIN_LENGTH] characters, at most [MAX_LENGTH], and not
 * digits only (no PINs). Characters are counted as Unicode code points of the normalised text
 * (see [nfc]), so the same passphrase passes or fails whichever keyboard typed it.
 */
object PassphraseRules {
    const val MIN_LENGTH = 10

    /** Also named in the string `passphrase_too_long`. */
    const val MAX_LENGTH = 1024

    fun check(passphrase: CharArray): PassphraseProblem? = withNfc(passphrase) { text ->
        val length = Character.codePointCount(text, 0, text.size)
        when {
            length < MIN_LENGTH -> PassphraseProblem.TOO_SHORT
            length > MAX_LENGTH -> PassphraseProblem.TOO_LONG
            text.all { it.isDigit() } -> PassphraseProblem.DIGITS_ONLY
            else -> null
        }
    }

    /** A new passphrase entered twice: the two entries must be the same text, and follow the rules. */
    fun checkNew(first: CharArray, second: CharArray): PassphraseProblem? {
        val same = withNfc(first) { a -> withNfc(second) { b -> a.contentEquals(b) } }
        return if (same) check(first) else PassphraseProblem.MISMATCH
    }

    /**
     * Whether [passphrase], entered to unlock, is worth checking at all: not empty and not longer
     * than any passphrase can be. Checked before an attempt is counted.
     */
    fun checkEntered(passphrase: CharArray): PassphraseProblem? = when {
        passphrase.isEmpty() -> PassphraseProblem.EMPTY
        withNfc(passphrase) { text -> Character.codePointCount(text, 0, text.size) } > MAX_LENGTH -> PassphraseProblem.TOO_LONG
        else -> null
    }
}

/**
 * PBKDF2-HMAC-SHA256 (RFC 8018, section 5.2) over `javax.crypto.Mac`. Implemented here rather
 * than through `SecretKeyFactory("PBKDF2WithHmacSHA256")` so that the passphrase is turned into
 * bytes (UTF-8) by this code, in an array that is zeroed afterwards, and the result never depends
 * on how a security provider converts characters. Checked against RFC test vectors and the JDK's
 * own implementation in the unit tests.
 */
object Pbkdf2 {
    private const val HASH_LENGTH = 32

    fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(iterations >= 1 && length >= 1)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val result = ByteArray(length)
        val u = ByteArray(HASH_LENGTH)
        val t = ByteArray(HASH_LENGTH)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(t)
            repeat(iterations - 1) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(HASH_LENGTH, length - offset)
            t.copyInto(result, offset, 0, n)
            offset += n
            block++
        }
        u.fill(0)
        t.fill(0)
        return result
    }
}

/** Overwrites a passphrase that is no longer needed. */
fun CharArray.wipe() = fill('\u0000')

/**
 * [chars] in Unicode Normalisation Form C: the array itself when it already is in that form (the
 * usual case), otherwise a new array, which the caller wipes.
 *
 * The same text can be typed as different code point sequences ("é" as one code point or as "e"
 * plus a combining accent), depending on the keyboard. Without normalisation a passphrase set
 * with one keyboard would not unlock with another, and there is no recovery. Every passphrase is
 * therefore normalised before its bytes are derived from, and before the rules count its length.
 *
 * What stays in memory:
 * - Text whose characters are all below U+0300 (every ASCII and Latin-1 passphrase) is in form C
 *   already: the first combining mark is U+0300, and form C changes no character below it. It is
 *   not handed to the normaliser at all, so nothing is copied.
 * - Other text is first asked about with `Normalizer.isNormalized` over a view of the array.
 *   Recent platform versions answer without copying; older ones (and the desktop JDK the unit
 *   tests run on) turn the view into a String first.
 * - Only text that is not normalised is normalised. `Normalizer.normalize` returns a String,
 *   which cannot be wiped: that is the residual copy, left to the garbage collector like the
 *   text field's own buffer. The array made from it is wiped by the caller.
 */
fun nfc(chars: CharArray): CharArray {
    if (chars.all { it < FIRST_COMPOSING_CHAR }) return chars
    val view = CharBuffer.wrap(chars)
    if (Normalizer.isNormalized(view, Normalizer.Form.NFC)) return chars
    return Normalizer.normalize(view, Normalizer.Form.NFC).toCharArray()
}

/** Runs [block] with the normalised form of [passphrase] (see [nfc]) and wipes the copy, if one was made. */
inline fun <T> withNfc(passphrase: CharArray, block: (CharArray) -> T): T {
    val text = nfc(passphrase)
    try {
        return block(text)
    } finally {
        if (text !== passphrase) text.wipe()
    }
}

/** The first character that can change under normalisation to form C (U+0300, the first combining mark). */
private const val FIRST_COMPOSING_CHAR = '̀'

/** UTF-8 bytes of [chars] without creating a String; the caller zeroes the result. */
fun utf8Bytes(chars: CharArray): ByteArray {
    val encoder = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val buffer: ByteBuffer = encoder.encode(CharBuffer.wrap(chars))
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    if (buffer.hasArray()) buffer.array().fill(0)
    return bytes
}

/**
 * The bytes a passphrase is derived from: UTF-8 of its normalised form (see [nfc]). The caller
 * zeroes the result. This encoding is permanent: changing it locks every user out.
 */
fun passphraseBytes(passphrase: CharArray): ByteArray = withNfc(passphrase, ::utf8Bytes)
