package com.snipsearch.ui.overlay

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BorderGlowTest {
    private val width = 1080f
    private val height = 2400f
    private val radius = 120f
    private val perimeter = RoundedPerimeter(width, height, radius, radius, radius, radius)

    @Test
    fun traceLapDurationIsSlowAndDeliberate() {
        assertTrue("lap should be between 3000ms and 4200ms, was $COMET_LAP_MILLIS", COMET_LAP_MILLIS in 3000L..4200L)
    }

    @Test
    fun mergeDurationIsQuickAcrossGapSizes() {
        val fullTailGap = traceMergeGapLap()
        val afterFullTail = mergeDurationMillis(fullTailGap)
        val afterInstantOcr = mergeDurationMillis(0.5f)
        assertTrue("full-tail merge should be 160..200ms, was $afterFullTail", afterFullTail in 160L..200L)
        assertTrue("instant-OCR merge should be 220..260ms, was $afterInstantOcr", afterInstantOcr in 220L..260L)
        assertTrue(afterFullTail <= afterInstantOcr)
    }

    @Test
    fun easingIsMonotonicAndMapsEndpoints() {
        assertEquals(0f, easedSegmentProgress(0f), 1e-6f)
        assertEquals(1f, easedSegmentProgress(1f), 1e-6f)
        var previous = -1f
        for (i in 0..1000) {
            val eased = easedSegmentProgress(i / 1000f)
            assertTrue("eased progress must not decrease at $i", eased > previous)
            previous = eased
        }
    }

    @Test
    fun speedIsLowestAtCornersAndHighestMidEdgeButNeverZero() {
        val atCorner = segmentSpeed(0f)
        val midEdge = segmentSpeed(0.5f)
        assertEquals(0.25f, atCorner, 1e-6f)
        assertEquals(1.75f, midEdge, 1e-6f)
        assertTrue(atCorner > 0f && atCorner < midEdge)
        for (i in 0..100) assertTrue(segmentSpeed(i / 100f) > 0f)
        assertEquals(atCorner, segmentSpeed(1f), 1e-6f)
    }

    @Test
    fun headSlowsThroughCornersAndSpeedsUpMidEdge() {
        val step = 0.0005f
        fun speed(lap: Float): Float {
            var delta = perimeter.distanceAtLap(lap + step) - perimeter.distanceAtLap(lap)
            if (delta < 0f) delta += perimeter.length
            return delta / step / perimeter.length
        }
        val topSegmentLength = (width - 2 * radius) + radius * PI.toFloat() / 2f
        val topRightCornerLap = topSegmentLength / perimeter.length
        val midTopEdge = speed(topRightCornerLap / 2f)
        val corner = speed(topRightCornerLap)
        assertTrue("corner $corner must be slower than mid-edge $midTopEdge", corner < midTopEdge)
        assertTrue(corner > 0.2f && corner < 0.5f)
    }

    @Test
    fun lapDistanceIsContinuousMonotonicAndCoversOnePerimeter() {
        var previous = perimeter.distanceAtLap(0f)
        for (i in 1 until 4000) {
            val distance = perimeter.distanceAtLap(i / 4000f)
            assertTrue(distance >= previous)
            previous = distance
        }
        val start = perimeter.distanceAtLap(0f)
        assertEquals(perimeter.length, perimeter.distanceAtLap(0.99999f) - start, perimeter.length * 0.001f)
    }

    @Test
    fun perimeterPointsLieOnEdgesAndCornerArcs() {
        val steps = 4000
        for (i in 0 until steps) {
            val p = perimeter.pointAt(perimeter.length * i / steps)
            assertTrue("inside rect: $p", p.x >= -0.01f && p.x <= width + 0.01f && p.y >= -0.01f && p.y <= height + 0.01f)
            val onEdge = abs(p.x) < 0.01f || abs(p.x - width) < 0.01f || abs(p.y) < 0.01f || abs(p.y - height) < 0.01f
            if (onEdge) continue
            val cx = if (p.x < width / 2) radius else width - radius
            val cy = if (p.y < height / 2) radius else height - radius
            assertEquals("corner arc radius at $p", radius, hypot(p.x - cx, p.y - cy), 0.05f)
        }
    }

    @Test
    fun perimeterIsClockwiseAndWrapsContinuously() {
        assertPoint(Offset(radius, 0f), perimeter.pointAt(0f))
        assertPoint(Offset(width - radius, 0f), perimeter.pointAt(width - 2 * radius))
        assertPoint(Offset(width, radius), perimeter.pointAt(width - 2 * radius + radius * PI.toFloat() / 2f))
        assertPoint(perimeter.pointAt(0f), perimeter.pointAt(perimeter.length))
        assertPoint(perimeter.pointAt(37f), perimeter.pointAt(37f + perimeter.length))
        assertPoint(perimeter.pointAt(perimeter.length - 37f), perimeter.pointAt(-37f))
        val expected = 2 * (width - 2 * radius) + 2 * (height - 2 * radius) + 2 * PI.toFloat() * radius
        assertEquals(expected, perimeter.length, 0.01f)
        val step = 3f
        var previous = perimeter.pointAt(0f)
        var d = step
        while (d <= perimeter.length + step) {
            val p = perimeter.pointAt(d)
            assertTrue("jump at $d", (p - previous).getDistance() <= step + 0.01f)
            previous = p
            d += step
        }
    }

    @Test
    fun squareCornersFallBackToStraightCorners() {
        val square = RoundedPerimeter(100f, 200f, 0f, 0f, 0f, 0f)
        assertEquals(600f, square.length, 0.001f)
        assertPoint(Offset(100f, 0f), square.pointAt(100f))
        assertPoint(Offset(100f, 200f), square.pointAt(300f))
        val wrapped = traceSegmentDistances(square, 0.05f, 0.28f, COMET_TAIL_SAMPLES)
        assertEquals(COMET_TAIL_SAMPLES, wrapped.size)
        for (i in 0 until wrapped.size - 1) {
            assertTrue("square distance must be non-increasing at $i", wrapped[i] > wrapped[i + 1])
        }
    }

    @Test
    fun cometsAreHalfAPerimeterApart() {
        for (i in 0..50) {
            val lap = i / 50f
            val first = perimeter.distanceAtLap(lap)
            val second = perimeter.distanceAtLap(wrap01(lap + 1f / COMET_COUNT))
            var apart = second - first
            while (apart < 0f) apart += perimeter.length
            assertEquals(perimeter.length / 2f, apart, perimeter.length * 0.001f)
        }
    }

    @Test
    fun traceDistancesAreMonotonicAcrossSeamAndLongerMidEdge() {
        val tailSpan = traceTailLapSpan()
        val wrapped = traceSegmentDistances(perimeter, 0.05f, tailSpan, COMET_TAIL_SAMPLES)
        assertEquals(COMET_TAIL_SAMPLES, wrapped.size)
        assertEquals(perimeter.distanceAtLap(0.05f), wrapped.first(), 1e-4f)
        for (i in 0 until wrapped.size - 1) {
            assertTrue("wrapped trace distances must strictly decrease at $i", wrapped[i] > wrapped[i + 1])
        }
        val wrappedLength = wrapped.first() - wrapped.last()
        assertTrue("trace length should be substantial, was $wrappedLength", wrappedLength > perimeter.length * 0.25f)
        assertTrue("trace length should stay below full perimeter", wrappedLength < perimeter.length * 0.50f)

        val rightEdgeCenterLap = ((width - 2 * radius) + radius * PI.toFloat() / 2f + (height - 2 * radius) / 2f) / perimeter.length
        val topRightCornerLap = ((width - 2 * radius) + radius * PI.toFloat() / 2f) / perimeter.length
        val edgeTrace = traceSegmentDistances(perimeter, rightEdgeCenterLap, 0.10f, COMET_TAIL_SAMPLES)
        val cornerTrace = traceSegmentDistances(perimeter, topRightCornerLap, 0.10f, COMET_TAIL_SAMPLES)
        val edgeSpan = edgeTrace.first() - edgeTrace.last()
        val cornerSpan = cornerTrace.first() - cornerTrace.last()
        assertTrue("edge span ($edgeSpan) should exceed corner span ($cornerSpan)", edgeSpan > cornerSpan)
    }

    @Test
    fun traceProfileFeathersLeadAndDecaysSmoothly() {
        val tailSpan = traceTailLapSpan()
        for (drawn in listOf(tailSpan, 0.08f)) {
            val leadLap = min(TRACE_LEAD_FRACTION * tailSpan, drawn * 0.40f)
            assertEquals(0f, traceProfile(0f, drawn, tailSpan).alphaScale, 1e-6f)
            assertEquals(0f, traceProfile(drawn, drawn, tailSpan).alphaScale, 1e-6f)

            val beforeLead = traceProfile(leadLap - 1e-5f, drawn, tailSpan)
            val afterLead = traceProfile(leadLap + 1e-5f, drawn, tailSpan)
            assertEquals(beforeLead.alphaScale, afterLead.alphaScale, 1e-3f)
            assertEquals(beforeLead.widthScale, afterLead.widthScale, 1e-3f)

            var prevAlpha = -1f
            for (i in 0..20) {
                val back = leadLap * i / 20f
                val sample = traceProfile(back, drawn, tailSpan)
                assertTrue("lead alpha must rise for drawn=$drawn at $back", sample.alphaScale >= prevAlpha - 1e-6f)
                assertTrue("width out of range at $back: ${sample.widthScale}", sample.widthScale in 0.45f..1f)
                prevAlpha = sample.alphaScale
            }

            prevAlpha = Float.MAX_VALUE
            for (i in 0..80) {
                val back = leadLap + (drawn - leadLap) * i / 80f
                val sample = traceProfile(back, drawn, tailSpan)
                assertTrue("tail alpha must decay for drawn=$drawn at $back", sample.alphaScale <= prevAlpha + 1e-6f)
                assertTrue("width out of range at $back: ${sample.widthScale}", sample.widthScale in 0.45f..1f)
                prevAlpha = sample.alphaScale
            }
        }
    }

    @Test
    fun mergeClosesGapExactlyAtOpposingTail() {
        val tailSpan = traceTailLapSpan()
        val gapLap = traceMergeGapLap()
        assertTrue("gapLap must be positive, was $gapLap", gapLap > 0f)
        assertEquals(1f / COMET_COUNT, tailSpan + gapLap, 1e-6f)

        for (lap in listOf(0.0f, 0.13f, 0.48f, 0.91f)) {
            val trace0HeadAtMerge = perimeter.cometPoint(wrap01(lap + gapLap))
            val trace1AnchoredTail = perimeter.cometPoint(wrap01(lap + 1f / COMET_COUNT - tailSpan))
            assertPoint(trace1AnchoredTail, trace0HeadAtMerge)

            val trace1HeadAtMerge = perimeter.cometPoint(wrap01(lap + 1f / COMET_COUNT + gapLap))
            val trace0AnchoredTail = perimeter.cometPoint(wrap01(lap - tailSpan))
            assertPoint(trace0AnchoredTail, trace1HeadAtMerge)
        }
    }

    @Test
    fun mergeRemovesTailDecayAndGrowsTailToFullBorder() {
        val tailSpan = traceTailLapSpan()

        for (drawn in listOf(tailSpan, 0.10f)) {
            val gap = (1f / COMET_COUNT) - drawn
            val extraMid = gap * 0.5f
            val arcMid = drawn + extraMid
            val leadLap = min(TRACE_LEAD_FRACTION * tailSpan, arcMid * 0.40f)

            // No blunt cut-off wall at the anchored tail end while gap is still open (p = 0.5).
            assertEquals(
                "anchored tail end must feather to 0 during mid-merge for drawn=$drawn",
                0f,
                traceProfile(arcMid, drawn, tailSpan, extraMid, mergeProgress = 0.5f).alphaScale,
                1e-6f,
            )

            // Newly swept zone behind the leading nose stays at full width with zero tail decay.
            val sweptSample = traceProfile(leadLap + extraMid * 0.5f, drawn, tailSpan, extraMid, mergeProgress = 0.5f)
            assertEquals(1f, sweptSample.widthScale, 1e-6f)
            assertEquals(1.06f, sweptSample.alphaScale, 1e-4f)

            // Continuity across the leading-nose boundary.
            val beforeLead = traceProfile(leadLap - 1e-5f, drawn, tailSpan, extraMid, mergeProgress = 0.5f)
            val afterLead = traceProfile(leadLap + 1e-5f, drawn, tailSpan, extraMid, mergeProgress = 0.5f)
            assertEquals(beforeLead.alphaScale, afterLead.alphaScale, 1e-3f)
            assertEquals(beforeLead.widthScale, afterLead.widthScale, 1e-3f)

            // Interior of the older tail grows thicker and brighter proportional to p.
            val olderMidOffset = (drawn - leadLap) * 0.5f
            val interiorLoading = traceProfile(leadLap + olderMidOffset, drawn, tailSpan, 0f, mergeProgress = 0f)
            val interiorMerging = traceProfile(leadLap + extraMid + olderMidOffset, drawn, tailSpan, extraMid, mergeProgress = 0.5f)
            assertTrue(
                "tail interior alpha should grow for drawn=$drawn: ${interiorMerging.alphaScale} vs ${interiorLoading.alphaScale}",
                interiorMerging.alphaScale > interiorLoading.alphaScale,
            )
            assertTrue(
                "tail interior width should grow for drawn=$drawn: ${interiorMerging.widthScale} vs ${interiorLoading.widthScale}",
                interiorMerging.widthScale > interiorLoading.widthScale,
            )

            // Settled at p = 1: full uniform border everywhere.
            val fullSpan = drawn + gap
            for (i in 0..50) {
                val back = fullSpan * i / 50f
                val settled = traceProfile(back, drawn, tailSpan, gap, mergeProgress = 1f)
                assertEquals("alpha at $back (drawn=$drawn)", 1f, settled.alphaScale, 1e-5f)
                assertEquals("width at $back (drawn=$drawn)", 1f, settled.widthScale, 1e-5f)
            }
        }

        // Edge case: OCR completed at frame 0 (drawn = 0f, full 0.5 lap sweep at p = 1f).
        for (i in 0..25) {
            val back = 0.5f * i / 25f
            val settled = traceProfile(back, 0f, tailSpan, 0.5f, mergeProgress = 1f)
            assertEquals(1f, settled.alphaScale, 1e-5f)
            assertEquals(1f, settled.widthScale, 1e-5f)
        }
    }

    @Test
    fun glowStackFallsOffSmoothlyFromTheEdge() {
        val layers = glowLayers(GLOW_LAYER_COUNT, GLOW_EXTENT_DP, GLOW_PEAK_ALPHA)
        assertEquals(GLOW_LAYER_COUNT, layers.size)
        assertTrue(layers.all { it.alpha in 0f..1f })
        assertEquals(GLOW_EXTENT_DP, layers.last().halfWidthDp, 1e-4f)
        assertEquals(GLOW_PEAK_ALPHA, glowOpacityAt(layers, 0f), 1e-4f)
        var previous = Float.MAX_VALUE
        var distance = 0f
        while (distance <= GLOW_EXTENT_DP) {
            val opacity = glowOpacityAt(layers, distance)
            assertTrue(opacity <= previous)
            assertTrue("step too coarse at $distance", previous == Float.MAX_VALUE || previous - opacity < 0.11f)
            previous = opacity
            distance += 0.25f
        }
        assertEquals(0f, glowOpacityAt(layers, GLOW_EXTENT_DP + 0.5f), 1e-6f)
    }

    private fun assertPoint(expected: Offset, actual: Offset) {
        assertEquals("x", expected.x, actual.x, 0.05f)
        assertEquals("y", expected.y, actual.y, 0.05f)
    }
}
