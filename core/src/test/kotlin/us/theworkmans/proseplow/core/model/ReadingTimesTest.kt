package us.theworkmans.proseplow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.model.ReadingTimes.Point

/**
 * The time a model takes to read a conversation from nothing, as a curve through measured
 * points: interpolated between them, extrapolated beyond them with a cost per token that keeps
 * growing as it was last seen to grow, and inverted exactly.
 */
class ReadingTimesTest {

    /** A real curve: Qwen3.5 0.8B on the tested phone, read in chunks of 32 tokens (the catalogue's own may change). */
    private val qwen = ReadingTimes(
        listOf(
            Point(2048, 26.0),
            Point(4096, 64.0),
            Point(6144, 113.0),
            Point(8192, 180.0),
            Point(10_240, 271.0),
            Point(12_288, 384.0),
            Point(14_336, 512.0),
            Point(15_872, 619.0),
        ),
    )

    @Test
    fun `at a measured point the time is the measurement, and between two points on the straight line`() {
        val times = ReadingTimes(listOf(Point(100, 1.0), Point(200, 3.0)))
        assertEquals(0.0, times.seconds(0), 0.0)
        assertEquals(0.5, times.seconds(50), 1e-12)
        assertEquals(1.0, times.seconds(100), 1e-12)
        assertEquals(2.0, times.seconds(150), 1e-12)
        assertEquals(3.0, times.seconds(200), 1e-12)
        for ((tokens, seconds) in listOf(2048 to 26.0, 4096 to 64.0, 8192 to 180.0, 15_872 to 619.0)) {
            assertEquals("at $tokens", seconds, qwen.seconds(tokens), 1e-9)
        }
        assertEquals("half way between 4,096 and 6,144", 88.5, qwen.seconds(5120), 1e-9)
    }

    @Test
    fun `beyond the last point a token costs more at the rate the last two segments show`() {
        // Segments of 0.01 and 0.02 s a token, middles 50 and 150: the cost grows by 0.0001 s a token.
        // From 200 to 300 that is 0.02 * 100 + 0.0001 / 2 * (150^2 - 50^2) = 2 + 1 seconds.
        val times = ReadingTimes(listOf(Point(100, 1.0), Point(200, 3.0)))
        assertEquals(6.0, times.seconds(300), 1e-9)
        assertEquals(3.0 + 0.02 * 10 + 0.0001 / 2 * (60.0 * 60 - 50.0 * 50), times.seconds(210), 1e-9)
    }

    @Test
    fun `a single point is a constant speed, and a cost that fell is not taken to fall further`() {
        val one = ReadingTimes(listOf(Point(100, 1.0)))
        assertEquals(10.0, one.seconds(1000), 1e-9)
        // 0.02 s a token, then 0.01: beyond the last point it stays at 0.01.
        val falling = ReadingTimes(listOf(Point(100, 2.0), Point(200, 3.0)))
        assertEquals(4.0, falling.seconds(300), 1e-9)
        assertEquals(13.0, falling.seconds(1200), 1e-9)
    }

    @Test
    fun `a flat curve reads at the speed it was made from`() {
        val flat = ReadingTimes.flat(90.0)
        assertEquals(4096 / 90.0, flat.seconds(4096), 1e-9)
        assertEquals(100_000 / 90.0, flat.seconds(100_000), 1e-9)
        assertEquals(10_800, flat.tokensWithin(120.0))
        assertThrows(IllegalArgumentException::class.java) { ReadingTimes.flat(0.0) }
        assertThrows(IllegalArgumentException::class.java) { ReadingTimes.flat(Double.NaN) }
    }

    @Test
    fun `the time rises strictly with every token, measured and extrapolated`() {
        var previous = -1.0
        for (tokens in 0..300_000) {
            val seconds = qwen.seconds(tokens)
            assertTrue("at $tokens", seconds > previous)
            previous = seconds
        }
        assertTrue(qwen.seconds(Int.MAX_VALUE).isFinite())
    }

    @Test
    fun `the time from one depth to another is the difference`() {
        assertEquals(116.0, qwen.secondsBetween(4096, 8192), 1e-9)
        assertEquals(0.0, qwen.secondsBetween(5000, 5000), 0.0)
        assertTrue("the same 256 tokens take longer deeper in", qwen.secondsBetween(8192, 8448) > 2 * qwen.secondsBetween(0, 256))
    }

    @Test
    fun `the tokens read within a time invert the time exactly`() {
        for (tokens in (0..20_000 step 7) + listOf(32_768, 65_536, 111_616, 262_144)) {
            assertEquals("at $tokens", tokens, qwen.tokensWithin(qwen.seconds(tokens)))
        }
        for (seconds in listOf(0.5, 26.0, 120.0, 619.5, 3600.0, 25_000.0)) {
            val tokens = qwen.tokensWithin(seconds)
            assertTrue(qwen.seconds(tokens) <= seconds && qwen.seconds(tokens + 1) > seconds)
        }
        assertEquals("two minutes lie between 6,144 (113 s) and 8,192 (180 s)", 6357, qwen.tokensWithin(120.0))
        assertEquals(0, qwen.tokensWithin(0.0))
        assertEquals(0, qwen.tokensWithin(-1.0))
        assertEquals(0, qwen.tokensWithin(Double.NaN))
        assertEquals(Int.MAX_VALUE, qwen.tokensWithin(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `a slower device takes the same curve that many times as long`() {
        val slower = qwen.scaled(1.5)
        for (tokens in listOf(1000, 4096, 15_872, 40_000)) assertEquals(1.5 * qwen.seconds(tokens), slower.seconds(tokens), 1e-6)
        assertEquals(qwen, qwen.scaled(1.0))
        assertEquals(qwen.tokensWithin(80.0), slower.tokensWithin(120.0))
        assertThrows(IllegalArgumentException::class.java) { qwen.scaled(0.0) }
        assertThrows(IllegalArgumentException::class.java) { qwen.scaled(Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `no points means not known, and points must rise in both tokens and seconds`() {
        assertFalse(ReadingTimes.UNKNOWN.isKnown)
        assertTrue(qwen.isKnown)
        assertThrows(IllegalStateException::class.java) { ReadingTimes.UNKNOWN.seconds(10) }
        assertThrows(IllegalStateException::class.java) { ReadingTimes.UNKNOWN.tokensWithin(10.0) }
        assertThrows(IllegalArgumentException::class.java) { qwen.seconds(-1) }
        for (bad in listOf(
            listOf(Point(0, 1.0)),
            listOf(Point(100, 0.0)),
            listOf(Point(100, 1.0), Point(100, 2.0)),
            listOf(Point(100, 2.0), Point(200, 2.0)),
            listOf(Point(200, 1.0), Point(100, 2.0)),
            listOf(Point(100, Double.POSITIVE_INFINITY)),
        )) {
            assertThrows(bad.toString(), IllegalArgumentException::class.java) { ReadingTimes(bad) }
        }
    }

    @Test
    fun `every bundled model has reading times`() {
        for (model in ModelCatalog.bundled) assertTrue(model.id, model.readingTimes.isKnown)
    }
}
