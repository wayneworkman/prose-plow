package us.theworkmans.proseplow.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class EnvelopeTest {
    private val random = SecureRandom()
    private val key = ByteArray(32).also(random::nextBytes)
    private val plaintext = "a chat that must never be readable on disk".toByteArray()

    @Test
    fun roundTripsAndHasTheDocumentedLayout() {
        val blob = Envelope.seal(key, "settings", plaintext, random)
        assertEquals('P'.code.toByte(), blob[0])
        assertEquals('P'.code.toByte(), blob[1])
        assertEquals('E'.code.toByte(), blob[2])
        assertEquals(0x01.toByte(), blob[3])
        assertEquals(Envelope.VERSION, blob[3])
        assertEquals(Envelope.HEADER_SIZE + Envelope.NONCE_SIZE + plaintext.size + Envelope.TAG_SIZE, blob.size)
        assertTrue(Envelope.looksEncrypted(blob))
        assertArrayEquals(plaintext, Envelope.open(key, "settings", blob))
    }

    /**
     * The permanent identifiers, pinned from outside: a blob sealed here opens with plain JCA
     * calls that spell out the magic, the version and the `proseplow:` prefix of the additional
     * authenticated data. If any of them changes, everything stored before becomes unreadable.
     */
    @Test
    fun theAdditionalAuthenticatedDataIsTheHeaderAndThePrefixedPurpose() {
        val blob = Envelope.seal(key, "session:abc", plaintext, random)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 4, 12))
        cipher.updateAAD(byteArrayOf(0x50, 0x50, 0x45, 0x01) + "proseplow:session:abc".toByteArray(Charsets.UTF_8))
        assertArrayEquals(plaintext, cipher.doFinal(blob, 16, blob.size - 16))
    }

    @Test
    fun everyEncryptionUsesAFreshNonce() {
        val a = Envelope.seal(key, "p", plaintext, random)
        val b = Envelope.seal(key, "p", plaintext, random)
        assertFalse(a.copyOfRange(4, 16).contentEquals(b.copyOfRange(4, 16)))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun aFlippedByteAnywhereIsDetected() {
        val blob = Envelope.seal(key, "p", plaintext, random)
        for (i in blob.indices) {
            val tampered = blob.copyOf()
            tampered[i] = (tampered[i].toInt() xor 0x01).toByte()
            assertThrows("byte $i", UndecryptableException::class.java) { Envelope.open(key, "p", tampered) }
        }
    }

    @Test
    fun aBlobForAnotherFileOrPurposeIsRejected() {
        val one = Envelope.seal(key, Purposes.of("session", "a"), plaintext, random)
        assertThrows(UndecryptableException::class.java) { Envelope.open(key, Purposes.of("session", "b"), one) }
        assertThrows(UndecryptableException::class.java) { Envelope.open(key, Purposes.of("settings"), one) }
        val settings = Envelope.seal(key, Purposes.of("settings"), plaintext, random)
        assertThrows(UndecryptableException::class.java) { Envelope.open(key, Purposes.of("session", "settings"), settings) }
    }

    @Test
    fun truncatedWrongKeyAndUnknownVersionAreRejected() {
        val blob = Envelope.seal(key, "p", plaintext, random)
        for (length in listOf(0, 3, 4, 16, 31, blob.size - 1)) {
            assertThrows("length $length", UndecryptableException::class.java) { Envelope.open(key, "p", blob.copyOf(length)) }
        }
        val other = ByteArray(32).also(random::nextBytes)
        assertThrows(UndecryptableException::class.java) { Envelope.open(other, "p", blob) }
        val future = blob.copyOf().also { it[3] = 2 }
        assertThrows(UndecryptableException::class.java) { Envelope.open(key, "p", future) }
    }

    @Test
    fun plaintextFilesDoNotLookEncrypted() {
        assertFalse(Envelope.looksEncrypted("# Notes".toByteArray()))
        assertFalse(Envelope.looksEncrypted("{\"messages\":[]}".toByteArray()))
        assertFalse(Envelope.looksEncrypted(ByteArray(0)))
    }

    @Test
    fun aDestroyedSessionCipherRefusesToWorkAndZeroesItsKey() {
        val dataKey = DataKey.generate(random)
        val crypto = DataCrypto(dataKey)
        val blob = crypto.seal("p", plaintext)
        assertArrayEquals(plaintext, crypto.open("p", blob))
        crypto.destroy()
        assertTrue(dataKey.bytes.all { it == 0.toByte() })
        assertThrows(KeyDestroyedException::class.java) { crypto.seal("p", plaintext) }
        assertThrows(KeyDestroyedException::class.java) { crypto.open("p", blob) }
    }

    @Test
    fun dataKeyNeverPrintsItsBytes() {
        assertEquals("DataKey(***)", DataKey.generate(random).toString())
    }
}

class PurposesTest {
    @Test
    fun purposesAreANamespaceAndAnOptionalId() {
        assertEquals("settings", Purposes.of("settings"))
        assertEquals("session:3f2a", Purposes.of("session", "3f2a"))
        assertEquals("an id may contain anything, colons too", "note:a:b", Purposes.of("note", "a:b"))
        assertEquals("session-index", Purposes.of("session-index"))
    }

    @Test
    fun aNamespaceCannotHoldAColonOrBeTheLocksOwn() {
        for (bad in listOf("", "a:b", "Session", "9lives", "with space", "dek")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Purposes.of(bad) }
            assertThrows(bad, IllegalArgumentException::class.java) { Purposes.of(bad, "id") }
        }
        assertThrows(IllegalArgumentException::class.java) { Purposes.of("session", "") }
        // So no store's purpose can ever equal the purpose the lock wraps the data key with.
        assertTrue(LockManager.PASSPHRASE_PURPOSE.startsWith("dek:"))
    }
}

class Pbkdf2Test {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun matchesTheRfc7914TestVector() {
        val expected = hex(
            "55 ac 04 6e 56 e3 08 9f ec 16 91 c2 25 44 b6 05 f9 41 85 21 6d de 04 65 e6 8b 9d 57 c2 0d ac bc " +
                "49 ca 9c cc f1 79 b6 45 99 16 64 b3 9d 77 ef 31 7c 71 b8 45 b1 e3 0b d5 09 11 20 41 d3 a1 97 83",
        )
        assertArrayEquals(expected, Pbkdf2.hmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64))
    }

    @Test
    fun matchesTheJdkImplementationIncludingNonAsciiPassphrases() {
        val random = SecureRandom()
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        for (passphrase in listOf("correct horse battery", "pässwörd ünïcödé 密码 🔑", "a".repeat(100))) {
            val salt = ByteArray(16).also(random::nextBytes)
            val chars = passphrase.toCharArray()
            val ours = Pbkdf2.hmacSha256(utf8Bytes(chars), salt, 2_000, 32)
            val jdk = factory.generateSecret(PBEKeySpec(chars, salt, 2_000, 256)).encoded
            assertArrayEquals(passphrase, jdk, ours)
        }
    }

    @Test
    fun utf8EncodingOfACharArray() {
        assertArrayEquals("ä€".toByteArray(Charsets.UTF_8), utf8Bytes("ä€".toCharArray()))
        val chars = "secret".toCharArray()
        chars.wipe()
        assertTrue(chars.all { it == '\u0000' })
    }
}

/** Unicode normalisation of passphrases: the same text unlocks whichever way a keyboard composed it. */
class PassphraseNormalisationTest {
    // "é" as one code point, and as "e" followed by the combining acute accent.
    private val composed = "café au lait"
    private val decomposed = "café au lait"

    @Test
    fun bothCompositionsOfTheSameTextGiveTheSameBytes() {
        assertFalse(composed == decomposed)
        assertArrayEquals(passphraseBytes(composed.toCharArray()), passphraseBytes(decomposed.toCharArray()))
        assertArrayEquals(composed.toByteArray(Charsets.UTF_8), passphraseBytes(decomposed.toCharArray()))
        // Hangul typed as separate jamo, and as the syllable they make.
        assertArrayEquals(passphraseBytes("한".toCharArray()), passphraseBytes("한".toCharArray()))
        // Different texts stay different: compatibility forms (the "ﬁ" ligature) are not folded.
        assertFalse(passphraseBytes("ﬁsh".toCharArray()).contentEquals(passphraseBytes("fish".toCharArray())))
    }

    @Test
    fun textThatIsAlreadyNormalisedIsNotCopied() {
        for (text in listOf("correct horse battery", "pässwörd ünïcödé", composed, "密码 🔑 and more", "")) {
            val chars = text.toCharArray()
            assertSame(text, chars, nfc(chars))
            assertEquals(text, String(chars))
        }
    }

    @Test
    fun textThatIsNotNormalisedIsCopiedAndTheCopyIsWiped() {
        val chars = decomposed.toCharArray()
        val normalised = nfc(chars)
        assertNotSame(chars, normalised)
        assertEquals(composed, String(normalised))
        assertEquals("the input is left alone: its owner wipes it", decomposed, String(chars))

        var seen: CharArray? = null
        val length = withNfc(chars) { text ->
            seen = text
            text.size
        }
        assertEquals(composed.length, length)
        assertTrue("the normalised copy is wiped when the block is done", seen!!.all { it == '\u0000' })
        assertEquals("and the input still is not", decomposed, String(chars))
    }

    /** The shortcut in [nfc]: nothing below U+0300 changes under normalisation, alone or in any sequence. */
    @Test
    fun everythingBelowTheFirstCombiningMarkIsAlreadyNormalised() {
        for (c in 0 until 0x300) {
            assertTrue("U+%04X".format(c), Normalizer.isNormalized(c.toChar().toString(), Normalizer.Form.NFC))
        }
        val random = Random(7)
        repeat(2_000) {
            val text = String(CharArray(1 + random.nextInt(40)) { random.nextInt(0x300).toChar() })
            assertTrue(Normalizer.isNormalized(text, Normalizer.Form.NFC))
        }
        assertFalse("the first character past it is not", Normalizer.isNormalized("é", Normalizer.Form.NFC))
    }

    @Test
    fun theRulesCountTheNormalisedText() {
        // Ten letters each carrying a combining accent: ten characters, not twenty.
        val tenAccented = "é".repeat(10).toCharArray()
        assertNull(PassphraseRules.check(tenAccented))
        assertEquals(PassphraseProblem.TOO_SHORT, PassphraseRules.check("é".repeat(9).toCharArray()))
        // 1,024 of them are within the limit although they are 2,048 chars.
        assertNull(PassphraseRules.checkEntered("é".repeat(PassphraseRules.MAX_LENGTH).toCharArray()))
        assertEquals(PassphraseProblem.TOO_LONG, PassphraseRules.checkEntered("é".repeat(PassphraseRules.MAX_LENGTH + 1).toCharArray()))
        // Entered twice with different compositions is the same passphrase.
        assertNull(PassphraseRules.checkNew(composed.toCharArray(), decomposed.toCharArray()))
    }
}

class PassphraseRulesTest {
    @Test
    fun atLeastTenCharactersAndNotDigitsOnly() {
        assertEquals(PassphraseProblem.TOO_SHORT, PassphraseRules.check("short pass".dropLast(1).toCharArray()))
        assertEquals(PassphraseProblem.TOO_SHORT, PassphraseRules.check(CharArray(0)))
        assertEquals(PassphraseProblem.DIGITS_ONLY, PassphraseRules.check("1234567890".toCharArray()))
        assertEquals(PassphraseProblem.DIGITS_ONLY, PassphraseRules.check("12345678901234567890".toCharArray()))
        assertNull(PassphraseRules.check("123456789a".toCharArray()))
        assertNull(PassphraseRules.check("ten chars!".toCharArray()))
        assertEquals("emoji count as one character each", PassphraseProblem.TOO_SHORT, PassphraseRules.check("🔑🔑🔑🔑🔑🔑🔑🔑🔑".toCharArray()))
        assertNull(PassphraseRules.check(CharArray(PassphraseRules.MAX_LENGTH) { 'a' }))
        assertEquals(PassphraseProblem.TOO_LONG, PassphraseRules.check(CharArray(PassphraseRules.MAX_LENGTH + 1) { 'a' }))
    }

    @Test
    fun aNewPassphraseMustBeEnteredTheSameTwice() {
        assertNull(PassphraseRules.checkNew("correct horse".toCharArray(), "correct horse".toCharArray()))
        assertEquals(PassphraseProblem.MISMATCH, PassphraseRules.checkNew("correct horse".toCharArray(), "correct house".toCharArray()))
        assertEquals(PassphraseProblem.MISMATCH, PassphraseRules.checkNew("correct horse".toCharArray(), "Correct horse".toCharArray()))
        assertEquals("the rules apply once the two agree", PassphraseProblem.DIGITS_ONLY, PassphraseRules.checkNew("1234567890".toCharArray(), "1234567890".toCharArray()))
        assertEquals(PassphraseProblem.MISMATCH, PassphraseRules.checkNew("short".toCharArray(), "other".toCharArray()))
    }

    @Test
    fun whatIsEnteredToUnlockOnlyHasToBePossible() {
        assertEquals(PassphraseProblem.EMPTY, PassphraseRules.checkEntered(CharArray(0)))
        assertNull("a short entry is simply wrong, and is counted", PassphraseRules.checkEntered("x".toCharArray()))
        assertEquals(PassphraseProblem.TOO_LONG, PassphraseRules.checkEntered(CharArray(PassphraseRules.MAX_LENGTH + 1) { 'a' }))
    }
}

class BackoffTest {
    @Test
    fun theFirstFailuresAreFreeThenTheDelayDoublesUpToAnHour() {
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), (0..4).map(Backoff::delayAfter))
        assertEquals(30_000L, Backoff.delayAfter(5))
        assertEquals(60_000L, Backoff.delayAfter(6))
        assertEquals(120_000L, Backoff.delayAfter(7))
        assertEquals(listOf(240_000L, 480_000L, 960_000L, 1_920_000L), (8..11).map(Backoff::delayAfter))
        assertEquals(Backoff.MAX_DELAY_MILLIS, Backoff.delayAfter(12))
        assertEquals(Backoff.MAX_DELAY_MILLIS, Backoff.delayAfter(1_000))
        assertEquals(Backoff.MAX_DELAY_MILLIS, Backoff.delayAfter(Int.MAX_VALUE))
        assertEquals(3_600_000L, Backoff.MAX_DELAY_MILLIS)
    }

    @Test
    fun bothClocksMustAgreeThatTheDelayHasPassed() {
        val clock = FakeClock()
        val config = testConfig(failedAttempts = 5, lastFailureWallMillis = clock.wall, lastFailureElapsedMillis = clock.elapsed)
        assertEquals(30_000L, Backoff.remaining(config, clock))
        clock.advance(10_000)
        assertEquals(20_000L, Backoff.remaining(config, clock))

        // Setting the wall clock forward does not help within the same boot.
        clock.wall += 3_600_000
        assertEquals(20_000L, Backoff.remaining(config, clock))

        // After a reboot (monotonic clock restarted) the full delay applies again, whatever the
        // wall clock says, and re-anchoring starts it from the new boot.
        val rebooted = FakeClock(wall = config.lastFailureWallMillis + 5_000, elapsed = 1_000)
        assertEquals(30_000L, Backoff.remaining(config, rebooted))
        rebooted.wall += 86_400_000
        assertEquals(30_000L, Backoff.remaining(config, rebooted))
        val reanchored = Backoff.reanchor(config, rebooted)!!
        assertEquals(1_000L, reanchored.lastFailureElapsedMillis)
        assertEquals(30_000L, Backoff.remaining(reanchored, rebooted))
        rebooted.advance(30_000)
        assertEquals(0L, Backoff.remaining(reanchored, rebooted))
        assertNull("nothing to re-anchor in the same boot", Backoff.reanchor(reanchored, rebooted))

        // A wall clock set back restarts the delay, but never makes it longer than one delay.
        val setBack = FakeClock(wall = config.lastFailureWallMillis - 86_400_000, elapsed = 1_000)
        assertEquals(30_000L, Backoff.remaining(config, setBack))

        clock.advance(20_000)
        clock.wall -= 3_600_000
        assertEquals(0L, Backoff.remaining(config, clock))
    }
}
