package com.snipsearch.ui.overlay

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
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
        assertTrue("lap should be at least 4000ms, was $COMET_LAP_MILLIS", COMET_LAP_MILLIS >= 4000L)
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
        val tailSpan = COMET_TAIL_MILLIS.toFloat() / COMET_LAP_MILLIS
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
        assertEquals(0f, traceProfile(0f).alphaScale, 1e-6f)
        assertEquals(0f, traceProfile(1f).alphaScale, 1e-6f)

        var prevAlpha = -1f
        for (i in 0..20) {
            val p = TRACE_LEAD_FRACTION * i / 20f
            val sample = traceProfile(p)
            assertTrue("lead alpha must rise at $p", sample.alphaScale >= prevAlpha)
            assertTrue("width out of range at $p: ${sample.widthScale}", sample.widthScale in 0.45f..1f)
            prevAlpha = sample.alphaScale
        }

        prevAlpha = Float.MAX_VALUE
        for (i in 0..80) {
            val p = TRACE_LEAD_FRACTION + (1f - TRACE_LEAD_FRACTION) * i / 80f
            val sample = traceProfile(p)
            assertTrue("tail alpha must decay at $p", sample.alphaScale <= prevAlpha)
            assertTrue("width out of range at $p: ${sample.widthScale}", sample.widthScale in 0.45f..1f)
            prevAlpha = sample.alphaScale
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
