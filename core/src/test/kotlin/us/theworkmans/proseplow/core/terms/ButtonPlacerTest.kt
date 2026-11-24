package us.theworkmans.proseplow.core.terms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The placement of the two buttons of a statement screen, over many seeds and several areas, in
 * pixels at a density of about 2.75 (a margin of 16 dp is 44 pixels, a gap of 24 dp is 66).
 */
class ButtonPlacerTest {

    private val margin = 44
    private val gap = 66

    private data class Area(val width: Int, val height: Int, val buttonWidth: Int, val buttonHeight: Int)

    private val areas = listOf(
        // A phone held upright, the lower half of the screen.
        Area(1080, 1000, 420, 132),
        // Upright with a large font: one button nearly as wide as the area.
        Area(1080, 1000, 980, 300),
        // Turned sideways: wide and low.
        Area(2400, 420, 420, 132),
        // Only just wide enough for two side by side and only just tall enough for one.
        Area(2 * margin + 2 * 420 + gap, 2 * margin + 132, 420, 132),
        // Only just tall enough for two one above the other, and too narrow for two side by side.
        Area(600, 2 * margin + 2 * 132 + gap, 420, 132),
    )

    private fun place(area: Area, seed: Int) =
        ButtonPlacer.place(area.width, area.height, area.buttonWidth, area.buttonHeight, margin, gap, Random(seed))

    private fun inside(place: ButtonPlace, area: Area, height: Int = area.height) =
        place.x >= margin && place.y >= margin && place.x + area.buttonWidth <= area.width - margin && place.y + area.buttonHeight <= height - margin

    /** The empty space between the two buttons along the axis on which they are furthest apart; negative when they overlap. */
    private fun separation(a: ButtonPlace, b: ButtonPlace, area: Area): Int {
        val across = kotlin.math.abs(a.x - b.x) - area.buttonWidth
        val vertical = kotlin.math.abs(a.y - b.y) - area.buttonHeight
        return maxOf(across, vertical)
    }

    @Test
    fun `both buttons are inside the area with the margin, never overlap, and keep the gap`() {
        for (area in areas) {
            repeat(2_000) { seed ->
                val placement = place(area, seed)
                assertFalse("$area can hold both", placement.stacked)
                assertEquals(area.height, placement.height)
                assertTrue("$area seed $seed: $placement", inside(placement.agree, area) && inside(placement.disagree, area))
                assertTrue("$area seed $seed: $placement", separation(placement.agree, placement.disagree, area) >= gap)
            }
        }
    }

    @Test
    fun `each button is on each side of the other in a fair share of the draws`() {
        // The first area allows every arrangement; the others only some, but always both orders.
        for (area in areas) {
            val draws = 4_000
            var agreeLeft = 0
            var agreeRight = 0
            var agreeAbove = 0
            var agreeBelow = 0
            repeat(draws) { seed ->
                val p = place(area, seed)
                if (p.agree.x + area.buttonWidth + gap <= p.disagree.x) agreeLeft++
                if (p.disagree.x + area.buttonWidth + gap <= p.agree.x) agreeRight++
                if (p.agree.y + area.buttonHeight + gap <= p.disagree.y) agreeAbove++
                if (p.disagree.y + area.buttonHeight + gap <= p.agree.y) agreeBelow++
            }
            // By symmetry each side of a pair is as likely as the other: within 10% of each other.
            val acrossShare = (agreeLeft + agreeRight).toDouble()
            if (acrossShare > 0) assertTrue("$area: left $agreeLeft, right $agreeRight", kotlin.math.abs(agreeLeft - agreeRight) <= 0.1 * acrossShare)
            val verticalShare = (agreeAbove + agreeBelow).toDouble()
            if (verticalShare > 0) assertTrue("$area: above $agreeAbove, below $agreeBelow", kotlin.math.abs(agreeAbove - agreeBelow) <= 0.1 * verticalShare)
            assertTrue("$area: the buttons are apart one way or the other in every draw", acrossShare + verticalShare >= draws)
        }
        val open = areas.first()
        val sides = (0 until 4_000).map { seed -> place(open, seed) }
        assertTrue("left of the other", sides.any { it.agree.x + open.buttonWidth + gap <= it.disagree.x })
        assertTrue("right of the other", sides.any { it.disagree.x + open.buttonWidth + gap <= it.agree.x })
        assertTrue("above the other", sides.any { it.agree.y + open.buttonHeight + gap <= it.disagree.y })
        assertTrue("below the other", sides.any { it.disagree.y + open.buttonHeight + gap <= it.agree.y })
    }

    @Test
    fun `every pair of places that fits is equally likely`() {
        // A small grid, so that every pair can be counted: positions 0..4 across and 0..3 down,
        // buttons of 2 by 1 with a gap of 1 and no margin.
        val width = 6
        val height = 4
        val counts = HashMap<ButtonPlacement, Int>()
        val draws = 200_000
        val random = Random(42)
        repeat(draws) {
            val p = ButtonPlacer.place(width, height, 2, 1, 0, 1, random)
            counts[p] = (counts[p] ?: 0) + 1
        }
        var fitting = 0
        for (x1 in 0..4) for (y1 in 0..3) for (x2 in 0..4) for (y2 in 0..3) {
            if (kotlin.math.abs(x1 - x2) >= 3 || kotlin.math.abs(y1 - y2) >= 2) fitting++
        }
        assertEquals("every pair that fits is drawn, and no other", fitting, counts.size)
        val expected = draws.toDouble() / fitting
        for ((pair, count) in counts) assertTrue("$pair drawn $count times, about $expected expected", kotlin.math.abs(count - expected) <= 0.25 * expected)
    }

    @Test
    fun `the same seed gives the same places`() {
        for (area in areas) assertEquals(place(area, 11), place(area, 11))
    }

    @Test
    fun `an area too small for the rules gives the stacked placement, in both orders across seeds`() {
        val small = listOf(
            // Too low for two one above the other and too narrow for two side by side.
            Area(800, 300, 420, 132),
            // Narrower than a button with its margins.
            Area(400, 1000, 420, 132),
            // Nothing at all.
            Area(0, 0, 420, 132),
        )
        for (area in small) {
            var agreeOnTop = 0
            repeat(400) { seed ->
                val p = place(area, seed)
                assertTrue("$area", p.stacked)
                assertEquals("one above the other", p.agree.x, p.disagree.x)
                assertEquals("the gap apart", area.buttonHeight + gap, kotlin.math.abs(p.agree.y - p.disagree.y))
                assertEquals(margin, minOf(p.agree.y, p.disagree.y))
                assertEquals("the area grows to hold them, and scrolls", maxOf(area.height, 2 * margin + 2 * area.buttonHeight + gap), p.height)
                if (p.agree.y < p.disagree.y) agreeOnTop++
            }
            assertTrue("$area: both orders, $agreeOnTop of 400 with I agree on top", agreeOnTop in 150..250)
        }
    }
}
