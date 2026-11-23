package us.theworkmans.proseplow.core.model

/**
 * How long a model takes to read a conversation of a given length from nothing, as measured
 * on one device. A model with attention looks at everything it has read for every token it
 * reads, so a token costs more the more tokens are already in memory, and the time grows
 * faster than the length: the length divided by the speed of a short prompt says far too
 * little.
 *
 * The curve is given by measured [points], strictly increasing in tokens and in seconds, and
 * begins at nothing read in no time. Between two points the seconds are interpolated on a
 * straight line. Beyond the last point the cost of a token keeps growing at the rate seen over
 * the last two segments: with `c1` and `c2` the average seconds per token of those segments and
 * `m1` and `m2` their middles, a token at depth `d` costs `c2 + g * (d - m2)` seconds, where
 * `g = max(0, (c2 - c1) / (m2 - m1))`, and that cost is summed from the last point on. With a
 * single segment `g` is 0, so a curve of one point is a constant speed. A cost never shrinks
 * with depth, so the curve is strictly increasing everywhere and [tokensWithin] can invert it.
 *
 * An empty list of points means that the times are not known ([isKnown] is false); such a curve
 * answers no question of time.
 *
 * @property points the measured points, without the implicit start at nothing.
 */
class ReadingTimes(val points: List<Point>) {

    /** One measurement: reading [tokens] from nothing took [seconds]. */
    data class Point(val tokens: Int, val seconds: Double)

    init {
        var tokens = 0
        var seconds = 0.0
        for (point in points) {
            require(point.tokens > tokens) { "the tokens of the points must be positive and strictly increasing" }
            require(point.seconds > seconds && point.seconds.isFinite()) { "the seconds of the points must be positive, finite and strictly increasing" }
            tokens = point.tokens
            seconds = point.seconds
        }
    }

    /** False for a curve without points: nothing is known of the times. */
    val isKnown: Boolean get() = points.isNotEmpty()

    /** Every point with the start at nothing in front: the ends of the segments. */
    private val ends: List<Point> = listOf(Point(0, 0.0)) + points

    /** The average cost of a token over the last segment, in seconds. */
    private val lastCost: Double

    /** The middle of the last segment, in tokens. */
    private val lastMiddle: Double

    /** How much the cost of a token grows with every token beyond the last point; never negative. */
    private val growth: Double

    init {
        if (ends.size < 2) {
            lastCost = 0.0
            lastMiddle = 0.0
            growth = 0.0
        } else {
            val (a, b) = ends[ends.size - 2] to ends[ends.size - 1]
            lastCost = (b.seconds - a.seconds) / (b.tokens - a.tokens)
            lastMiddle = (a.tokens + b.tokens) / 2.0
            growth = if (ends.size < 3) {
                0.0
            } else {
                val before = ends[ends.size - 3]
                val earlierCost = (a.seconds - before.seconds) / (a.tokens - before.tokens)
                val earlierMiddle = (before.tokens + a.tokens) / 2.0
                maxOf(0.0, (lastCost - earlierCost) / (lastMiddle - earlierMiddle))
            }
        }
    }

    /**
     * The seconds it takes to read [tokens] from nothing. Strictly increasing in [tokens].
     *
     * @throws IllegalStateException when the times are not known.
     */
    fun seconds(tokens: Int): Double {
        require(tokens >= 0) { "a number of tokens cannot be negative" }
        check(isKnown) { "the reading times are not known" }
        val last = ends.last()
        if (tokens >= last.tokens) {
            // The integral of lastCost + growth * (d - lastMiddle) from the last point to tokens.
            val beyond = (tokens - last.tokens).toDouble()
            val from = last.tokens - lastMiddle
            val to = tokens - lastMiddle
            return last.seconds + lastCost * beyond + growth / 2 * (to * to - from * from)
        }
        val upper = ends.indexOfFirst { it.tokens > tokens }
        val (a, b) = ends[upper - 1] to ends[upper]
        return a.seconds + (b.seconds - a.seconds) * (tokens - a.tokens) / (b.tokens - a.tokens)
    }

    /** The seconds it takes to read on from [from] tokens in memory to [to]: the difference of the two [seconds]. */
    fun secondsBetween(from: Int, to: Int): Double = seconds(to) - seconds(from)

    /**
     * The most tokens that can be read from nothing within [seconds]: the largest `n` with
     * `seconds(n) <= seconds`, found by search, so that it is the exact inverse of [seconds].
     * 0 for no time, and [Int.MAX_VALUE] at most.
     *
     * @throws IllegalStateException when the times are not known.
     */
    fun tokensWithin(seconds: Double): Int {
        check(isKnown) { "the reading times are not known" }
        if (!(seconds > 0)) return 0
        if (seconds(Int.MAX_VALUE) <= seconds) return Int.MAX_VALUE
        var low = 0 // seconds(low) <= seconds always holds.
        var high = Int.MAX_VALUE // seconds(high) > seconds always holds.
        while (high - low > 1) {
            val middle = low + (high - low) / 2
            if (seconds(middle) <= seconds) low = middle else high = middle
        }
        return low
    }

    /** The same curve on a device that takes [factor] times as long for everything: every time multiplied by it. */
    fun scaled(factor: Double): ReadingTimes {
        require(factor > 0 && factor.isFinite()) { "a factor must be positive and finite" }
        return ReadingTimes(points.map { it.copy(seconds = it.seconds * factor) })
    }

    override fun equals(other: Any?): Boolean = other is ReadingTimes && other.points == points

    override fun hashCode(): Int = points.hashCode()

    override fun toString(): String = "ReadingTimes($points)"

    companion object {
        /** Nothing is known of the times. */
        val UNKNOWN: ReadingTimes = ReadingTimes(emptyList())

        /**
         * A curve that knows only a constant speed: every token costs the same, whatever is in
         * memory. It stands in where only the speed of one prompt is known.
         */
        fun flat(tokensPerSecond: Double): ReadingTimes {
            require(tokensPerSecond > 0 && tokensPerSecond.isFinite()) { "a speed must be positive and finite" }
            return ReadingTimes(listOf(Point(FLAT_POINT_TOKENS, FLAT_POINT_TOKENS / tokensPerSecond)))
        }

        /** Where the single point of a [flat] curve lies; any length would describe the same curve. */
        private const val FLAT_POINT_TOKENS = 1024
    }
}
