package us.theworkmans.proseplow.core.terms

import kotlin.random.Random

/** The top left corner of a button, in the units of the area it is placed in, from the area's top left corner. */
data class ButtonPlace(val x: Int, val y: Int)

/**
 * Where the two buttons of a statement go. [height] is how tall the area has to be to hold them:
 * the area's own height, or more when they are [stacked] because the area could not hold them
 * side by side or one above the other with the margin and the gap kept, in which case the area
 * scrolls.
 */
data class ButtonPlacement(val agree: ButtonPlace, val disagree: ButtonPlace, val stacked: Boolean, val height: Int)

/**
 * The random placement of the two buttons of a statement screen, "I agree" and "I do not agree",
 * in the lower half of the screen. Both have the same size. The rules:
 *
 * - each lies wholly inside the area, at least `margin` from its edges;
 * - they never overlap, and at least `gap` of empty space separates them: their horizontal
 *   distance or their vertical distance (edge to edge) is at least `gap`;
 * - within those rules every pair of places is equally likely, so that either button can be to
 *   the left or the right of the other, above it or below it, and nothing about one screen says
 *   anything about the next.
 *
 * The draw is exact, not by trying again until a draw fits: whether two places conflict depends
 * on the horizontal and the vertical distance separately (they conflict only when both are
 * short), so the pairs that conflict are the product of the horizontal pairs that are close and
 * the vertical pairs that are close, and the pairs that fit can be counted and drawn from
 * directly. That keeps the time bounded however narrowly the area holds the two.
 *
 * When no pair of places fits, the buttons are put one above the other, `gap` apart, at the
 * horizontal middle, in random order, and [ButtonPlacement.height] says how tall the area then
 * has to be.
 */
object ButtonPlacer {
    fun place(
        areaWidth: Int,
        areaHeight: Int,
        buttonWidth: Int,
        buttonHeight: Int,
        margin: Int,
        gap: Int,
        random: Random,
    ): ButtonPlacement {
        require(areaWidth >= 0 && areaHeight >= 0 && buttonWidth >= 0 && buttonHeight >= 0 && margin >= 0 && gap >= 0)
        // The number of places along each axis: positions margin .. area - margin - button.
        val columns = areaWidth - 2 * margin - buttonWidth + 1
        val rows = areaHeight - 2 * margin - buttonHeight + 1
        if (columns <= 0 || rows <= 0) return stacked(areaWidth, areaHeight, buttonWidth, buttonHeight, margin, gap, random)
        // Two places along an axis are far enough apart when they differ by at least this much.
        val apartX = buttonWidth + gap
        val apartY = buttonHeight + gap
        val allX = columns.toLong() * columns
        val allY = rows.toLong() * rows
        val farX = pairsApart(columns, apartX)
        val farY = pairsApart(rows, apartY)
        val closeX = allX - farX
        // Pairs that fit: far apart across (any vertical pair), or close across and far apart vertically.
        val acrossFits = farX * allY
        val verticalFits = closeX * farY
        val total = acrossFits + verticalFits
        if (total == 0L) return stacked(areaWidth, areaHeight, buttonWidth, buttonHeight, margin, gap, random)
        val (xs, ys) = if (random.nextLong(total) < acrossFits) {
            drawPair(columns, apartX, far = true, random) to drawPair(rows, apartY, far = null, random)
        } else {
            drawPair(columns, apartX, far = false, random) to drawPair(rows, apartY, far = true, random)
        }
        return ButtonPlacement(
            agree = ButtonPlace(margin + xs.first, margin + ys.first),
            disagree = ButtonPlace(margin + xs.second, margin + ys.second),
            stacked = false,
            height = areaHeight,
        )
    }

    /** One above the other, [gap] apart, centred across, in random order. */
    private fun stacked(areaWidth: Int, areaHeight: Int, buttonWidth: Int, buttonHeight: Int, margin: Int, gap: Int, random: Random): ButtonPlacement {
        val x = maxOf(margin, (areaWidth - buttonWidth) / 2)
        val upper = ButtonPlace(x, margin)
        val lower = ButtonPlace(x, margin + buttonHeight + gap)
        val agreeOnTop = random.nextBoolean()
        return ButtonPlacement(
            agree = if (agreeOnTop) upper else lower,
            disagree = if (agreeOnTop) lower else upper,
            stacked = true,
            height = maxOf(areaHeight, 2 * margin + 2 * buttonHeight + gap),
        )
    }

    /** The ordered pairs of positions among [count] whose distance is at least [apart]. */
    private fun pairsApart(count: Int, apart: Int): Long {
        // For a distance d > 0 there are count - d pairs in each order.
        val from = maxOf(apart, 1)
        if (from >= count) return if (apart <= 0) count.toLong() * count else 0
        val n = (count - from).toLong()
        val apartPairs = n * (n + 1) // 2 * (1 + 2 + ... + n)
        return if (apart <= 0) apartPairs + count else apartPairs
    }

    /**
     * An ordered pair of positions among [count], drawn uniformly from those whose distance is at
     * least [apart] ([far] true), less than it ([far] false), or any ([far] null). There are
     * `count` pairs at distance 0 and `2 * (count - d)` at each distance d above it; a distance is
     * drawn by those weights, then which of its pairs.
     */
    private fun drawPair(count: Int, apart: Int, far: Boolean?, random: Random): Pair<Int, Int> {
        fun allowed(distance: Int) = when (far) {
            null -> true
            true -> distance >= apart
            false -> distance < apart
        }
        fun weight(distance: Int): Long = if (distance == 0) count.toLong() else 2L * (count - distance)
        var total = 0L
        for (distance in 0 until count) if (allowed(distance)) total += weight(distance)
        check(total > 0) { "No pair to draw from" }
        var pick = random.nextLong(total)
        for (distance in 0 until count) {
            if (!allowed(distance)) continue
            val w = weight(distance)
            if (pick >= w) {
                pick -= w
                continue
            }
            if (distance == 0) return pick.toInt() to pick.toInt()
            // The first half of the weight: the first position is the lower one; the second half: the higher.
            val lower = (pick % (count - distance)).toInt()
            return if (pick < count - distance) lower to lower + distance else lower + distance to lower
        }
        error("unreachable")
    }
}
