package com.snipsearch.ui.overlay

import android.provider.Settings
import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.snipsearch.ui.theme.SnipColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal const val GLOW_FADE_IN_MILLIS = 300
internal const val TRACE_FADE_IN_MILLIS = 220
internal const val COMET_LAP_MILLIS = 4600L
internal const val COMET_TAIL_MILLIS = 1750L
internal const val COMET_COUNT = 2
internal const val COMET_TAIL_SAMPLES = 52
internal const val COMET_SLOWDOWN = 0.75f
internal const val TRACE_LEAD_FRACTION = 0.05f
internal const val GLOW_LAYER_COUNT = 16
internal const val GLOW_EXTENT_DP = 14f
internal const val GLOW_PEAK_ALPHA = 0.66f

private const val LAP_NANOS = COMET_LAP_MILLIS * 1_000_000L

private val glowColor = SnipColors.SnipBlueGlow

private enum class GlowPhase {
    IDLE_FADE_IN,
    LOADING,
    MERGING,
    SETTLED,
}

internal fun traceTailLapSpan(): Float = COMET_TAIL_MILLIS.toFloat() / COMET_LAP_MILLIS

internal fun traceMergeGapLap(): Float = (1f / COMET_COUNT) - traceTailLapSpan()

/**
 * Soft glow that hugs all four screen edges and follows the display's rounded corners. While
 * [loading] is true, two luminous traces sweep around the perimeter; when loading completes,
 * their leading fronts continue to close the gap to each other's anchored tails while the thinner
 * tail ends grow seamlessly into the steady border glow. The composable is non-interactive and
 * stops its frame loop once settled.
 */
@Composable
internal fun BorderGlow(loading: Boolean, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val traceWanted = loading && !reducedMotion
    val tailSpan = traceTailLapSpan()
    val gapLap = traceMergeGapLap()

    var phase by remember { mutableStateOf(if (traceWanted) GlowPhase.LOADING else GlowPhase.IDLE_FADE_IN) }
    val traceAlpha = remember { Animatable(0f) }
    val steadyGlowAlpha = remember { Animatable(0f) }
    var elapsedNanos by remember { mutableLongStateOf(0L) }
    var mergeStartNanos by remember { mutableLongStateOf(0L) }
    var mergeStartLap by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(traceWanted) {
        if (traceWanted) {
            val wasSettled = phase == GlowPhase.SETTLED || steadyGlowAlpha.value > 0f
            phase = GlowPhase.LOADING
            if (wasSettled) {
                traceAlpha.snapTo(0f)
                coroutineScope {
                    launch { steadyGlowAlpha.animateTo(0f, tween(TRACE_FADE_IN_MILLIS, easing = FastOutSlowInEasing)) }
                    launch { traceAlpha.animateTo(1f, tween(TRACE_FADE_IN_MILLIS, easing = FastOutSlowInEasing)) }
                }
            } else {
                traceAlpha.animateTo(1f, tween(TRACE_FADE_IN_MILLIS, easing = FastOutSlowInEasing))
            }
        } else {
            when (phase) {
                GlowPhase.LOADING -> {
                    mergeStartNanos = elapsedNanos
                    mergeStartLap = (elapsedNanos % LAP_NANOS).toFloat() / LAP_NANOS
                    phase = GlowPhase.MERGING
                    if (traceAlpha.value < 1f) {
                        traceAlpha.animateTo(1f, tween(120, easing = FastOutSlowInEasing))
                    }
                }
                GlowPhase.IDLE_FADE_IN -> {
                    steadyGlowAlpha.animateTo(1f, tween(GLOW_FADE_IN_MILLIS, easing = FastOutSlowInEasing))
                    phase = GlowPhase.SETTLED
                }
                GlowPhase.MERGING, GlowPhase.SETTLED -> Unit
            }
        }
    }

    val running = phase == GlowPhase.LOADING || phase == GlowPhase.MERGING
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        elapsedNanos = 0L
        var start = -1L
        while (true) {
            withFrameNanos { now ->
                if (start < 0L) start = now
                elapsedNanos = now - start
            }
            if (phase == GlowPhase.MERGING) {
                val deltaLap = ((elapsedNanos - mergeStartNanos).coerceAtLeast(0L)).toFloat() / LAP_NANOS
                if (deltaLap >= gapLap && traceAlpha.value >= 0.999f) {
                    steadyGlowAlpha.snapTo(1f)
                    phase = GlowPhase.SETTLED
                    break
                }
            }
        }
    }

    val layers = remember { glowLayers(GLOW_LAYER_COUNT, GLOW_EXTENT_DP, GLOW_PEAK_ALPHA) }
    val tracePath = remember { Path() }
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    var radii by remember { mutableStateOf(CornerRadii.None) }
    val outline = remember(layoutSize, radii) {
        if (layoutSize.width <= 0 || layoutSize.height <= 0) {
            null
        } else {
            RoundedPerimeter(
                layoutSize.width.toFloat(),
                layoutSize.height.toFloat(),
                radii.topLeft,
                radii.topRight,
                radii.bottomRight,
                radii.bottomLeft,
            )
        }
    }
    val borderPath = remember(outline) {
        outline?.let {
            Path().apply {
                addRoundRect(
                    RoundRect(
                        0f,
                        0f,
                        it.width,
                        it.height,
                        CornerRadius(it.topLeft),
                        CornerRadius(it.topRight),
                        CornerRadius(it.bottomRight),
                        CornerRadius(it.bottomLeft),
                    ),
                )
            }
        }
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged {
                layoutSize = it
                radii = readDisplayCornerRadii(view)
            },
    ) {
        val perimeter = outline ?: return@Canvas
        val closedPath = borderPath ?: return@Canvas

        when (phase) {
            GlowPhase.IDLE_FADE_IN -> drawGlowStack(closedPath, layers, steadyGlowAlpha.value)
            GlowPhase.SETTLED -> drawGlowStack(closedPath, layers, 1f)
            GlowPhase.LOADING -> {
                val steadyOpacity = steadyGlowAlpha.value
                if (steadyOpacity > 0f) {
                    drawGlowStack(closedPath, layers, steadyOpacity)
                }
                val traceOpacity = traceAlpha.value
                if (traceOpacity > 0f) {
                    val lap = (elapsedNanos % LAP_NANOS).toFloat() / LAP_NANOS
                    repeat(COMET_COUNT) { index ->
                        val headLap = wrap01(lap + index.toFloat() / COMET_COUNT)
                        drawGlowTrace(perimeter, layers, headLap, tailSpan, 0f, 0f, traceOpacity, tracePath)
                    }
                }
            }
            GlowPhase.MERGING -> {
                val traceOpacity = traceAlpha.value
                if (traceOpacity > 0f) {
                    val deltaLap = ((elapsedNanos - mergeStartNanos).coerceAtLeast(0L)).toFloat() / LAP_NANOS
                    val extraLap = deltaLap.coerceAtMost(gapLap)
                    val mergeProgress = if (gapLap > 0f) (extraLap / gapLap).coerceIn(0f, 1f) else 1f
                    if (mergeProgress >= 1f) {
                        drawGlowStack(closedPath, layers, traceOpacity)
                    } else {
                        repeat(COMET_COUNT) { index ->
                            val headLap = wrap01(mergeStartLap + index.toFloat() / COMET_COUNT + extraLap)
                            drawGlowTrace(perimeter, layers, headLap, tailSpan, extraLap, mergeProgress, traceOpacity, tracePath)
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawGlowStack(path: Path, layers: List<GlowLayer>, alpha: Float) {
    if (alpha <= 0f) return
    for (layer in layers) {
        drawPath(
            path,
            glowColor.copy(alpha = (layer.alpha * alpha).coerceIn(0f, 1f)),
            style = Stroke(2f * layer.halfWidthDp.dp.toPx()),
        )
    }
}

private fun DrawScope.drawGlowTrace(
    outline: RoundedPerimeter,
    layers: List<GlowLayer>,
    headLap: Float,
    tailSpan: Float,
    extraLap: Float,
    mergeProgress: Float,
    opacity: Float,
    scratchPath: Path,
) {
    val arcSpan = tailSpan + extraLap
    val samples = ((COMET_TAIL_SAMPLES * (arcSpan / tailSpan)).toInt()).coerceIn(COMET_TAIL_SAMPLES, 72)
    val distances = traceSegmentDistances(outline, headLap, arcSpan, samples, COMET_SLOWDOWN)
    val segments = samples - 1
    for (i in 0 until segments) {
        val dStart = distances[i + 1]
        val dEnd = distances[i]
        if (dEnd - dStart <= 1e-3f) continue
        val backLap = arcSpan * (i + 0.5f) / segments
        val profile = traceProfile(backLap, tailSpan, extraLap, mergeProgress)
        val segAlpha = profile.alphaScale * opacity
        if (segAlpha <= 0.002f) continue
        scratchPath.rewind()
        outline.appendSegment(scratchPath, dStart, dEnd)
        for (layer in layers) {
            drawPath(
                scratchPath,
                glowColor.copy(alpha = (layer.alpha * segAlpha).coerceIn(0f, 1f)),
                style = Stroke(
                    width = 2f * layer.halfWidthDp.dp.toPx() * profile.widthScale,
                    cap = StrokeCap.Butt,
                    join = StrokeJoin.Miter,
                ),
            )
        }
    }
}

internal data class CornerRadii(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
) {
    companion object {
        val None = CornerRadii(0f, 0f, 0f, 0f)
    }
}

private fun readDisplayCornerRadii(view: View): CornerRadii {
    val insets = view.rootWindowInsets ?: return CornerRadii.None
    fun radius(position: Int) = insets.getRoundedCorner(position)?.radius?.toFloat() ?: 0f
    return CornerRadii(
        radius(RoundedCorner.POSITION_TOP_LEFT),
        radius(RoundedCorner.POSITION_TOP_RIGHT),
        radius(RoundedCorner.POSITION_BOTTOM_RIGHT),
        radius(RoundedCorner.POSITION_BOTTOM_LEFT),
    )
}

/** One stroke of the glow stack. [halfWidthDp] is the inward reach from the path. */
internal data class GlowLayer(val halfWidthDp: Float, val alpha: Float)

/** Opacity and stroke-width multipliers along a glowing perimeter trace (`0` = tip, `1` = tail). */
internal data class TraceSampleProfile(val alphaScale: Float, val widthScale: Float)

private fun smoothstep(x: Float): Float {
    val u = x.coerceIn(0f, 1f)
    return u * u * (3f - 2f * u)
}

/**
 * Computes opacity and stroke-width scales [backLap] behind the leading tip for a trace with
 * nominal loading span [tailSpan]. During loading (`extraLap = 0f, mergeProgress = 0f`) the
 * leading [TRACE_LEAD_FRACTION] feathers up smoothly and the remainder decays along the tail.
 * During completion merge (`mergeProgress` advancing `0 -> 1` in step with `extraLap` closing
 * the gap to the opposing trace), newly swept border behind the leading nose retains full glow
 * without tail decay while the thinner tail grows to match the settled border (`1f, 1f`).
 */
internal fun traceProfile(
    backLap: Float,
    tailSpan: Float,
    extraLap: Float = 0f,
    mergeProgress: Float = 0f,
): TraceSampleProfile {
    val leadLap = TRACE_LEAD_FRACTION * tailSpan
    val p = mergeProgress.coerceIn(0f, 1f)
    val peakAlpha = 1.12f + (1f - 1.12f) * p
    if (backLap < leadLap) {
        val lead = smoothstep(backLap / leadLap)
        val noseMerge = smoothstep((p - 0.70f) / 0.30f)
        val loadingWidth = 0.45f + 0.55f * lead
        return TraceSampleProfile(
            alphaScale = peakAlpha * (lead + (1f - lead) * noseMerge),
            widthScale = loadingWidth + (1f - loadingWidth) * noseMerge,
        )
    }
    val tailDecaySpan = (tailSpan - leadLap).coerceAtLeast(1e-5f)
    val decayBack = (backLap - leadLap - extraLap).coerceAtLeast(0f)
    val t = (decayBack / tailDecaySpan).coerceIn(0f, 1f)
    val baseAlpha = (1f - t).pow(1.45f)
    val loadingWidth = 0.50f + 0.50f * (1f - t).pow(0.7f)
    return TraceSampleProfile(
        alphaScale = peakAlpha * (baseAlpha + (1f - baseAlpha) * p),
        widthScale = loadingWidth + (1f - loadingWidth) * p,
    )
}

/**
 * Stroke stack whose composite opacity falls off smoothly from [peakAlpha] at the path to zero
 * at [extentDp]. Layers are ordered from the narrowest to the widest. Each layer's alpha is
 * chosen so that stacking all layers that cover a given distance reproduces the target profile.
 */
internal fun glowLayers(count: Int, extentDp: Float, peakAlpha: Float): List<GlowLayer> {
    val composite = FloatArray(count) { index ->
        val distance = index.toFloat() / count
        peakAlpha * (1f - distance).pow(2.4f)
    }
    return List(count) { index ->
        val covered = composite[index]
        val outer = if (index + 1 < count) composite[index + 1] else 0f
        GlowLayer(extentDp * (index + 1) / count, 1f - (1f - covered) / (1f - outer))
    }
}

/** Composite opacity at [distanceDp] from the path for a stack made by [glowLayers]. */
internal fun glowOpacityAt(layers: List<GlowLayer>, distanceDp: Float): Float {
    var transparent = 1f
    for (layer in layers) if (layer.halfWidthDp >= distanceDp) transparent *= 1f - layer.alpha
    return 1f - transparent
}

internal fun wrap01(value: Float): Float = value - floor(value)

/** Eased progress within a lap segment. Speed is `1 - k cos(2 pi f)`: slowest at the ends. */
internal fun easedSegmentProgress(f: Float, k: Float = COMET_SLOWDOWN): Float =
    f - k * sin(2f * PI.toFloat() * f) / (2f * PI.toFloat())

/** Derivative of [easedSegmentProgress], relative to the mean speed. */
internal fun segmentSpeed(f: Float, k: Float = COMET_SLOWDOWN): Float = 1f - k * cos(2f * PI.toFloat() * f)

/**
 * Clockwise rounded-rectangle outline starting at the left end of the top edge. Distances wrap
 * around [length]. Radii are clamped so adjacent corners fit on each side.
 */
internal class RoundedPerimeter(
    val width: Float,
    val height: Float,
    topLeft: Float,
    topRight: Float,
    bottomRight: Float,
    bottomLeft: Float,
) {
    private val limit = min(width, height) / 2f
    val topLeft = topLeft.coerceIn(0f, limit)
    val topRight = topRight.coerceIn(0f, limit)
    val bottomRight = bottomRight.coerceIn(0f, limit)
    val bottomLeft = bottomLeft.coerceIn(0f, limit)

    private class Piece(
        val start: Float,
        val length: Float,
        val arcCenter: Offset?,
        val radius: Float,
        val from: Offset,
        val to: Offset,
        val startAngle: Float,
    )

    private val pieces: List<Piece>
    val length: Float

    /** Distance of the middle of each corner arc: top-left (as a negative distance), top-right, bottom-right, bottom-left. */
    private val cornerMids: FloatArray

    init {
        val list = ArrayList<Piece>(8)
        var cursor = 0f
        fun edge(from: Offset, to: Offset) {
            val len = (to - from).getDistance()
            list += Piece(cursor, len, null, 0f, from, to, 0f)
            cursor += len
        }
        fun arc(center: Offset, radius: Float, startAngle: Float) {
            val len = radius * (PI.toFloat() / 2f)
            list += Piece(cursor, len, center, radius, Offset.Zero, Offset.Zero, startAngle)
            cursor += len
        }
        val halfPi = PI.toFloat() / 2f
        edge(Offset(this.topLeft, 0f), Offset(width - this.topRight, 0f))
        arc(Offset(width - this.topRight, this.topRight), this.topRight, -halfPi)
        edge(Offset(width, this.topRight), Offset(width, height - this.bottomRight))
        arc(Offset(width - this.bottomRight, height - this.bottomRight), this.bottomRight, 0f)
        edge(Offset(width - this.bottomRight, height), Offset(this.bottomLeft, height))
        arc(Offset(this.bottomLeft, height - this.bottomLeft), this.bottomLeft, halfPi)
        edge(Offset(0f, height - this.bottomLeft), Offset(0f, this.topLeft))
        arc(Offset(this.topLeft, this.topLeft), this.topLeft, 2f * halfPi)
        pieces = list
        length = cursor
        cornerMids = floatArrayOf(
            pieces[7].start + pieces[7].length / 2f - cursor,
            pieces[1].start + pieces[1].length / 2f,
            pieces[3].start + pieces[3].length / 2f,
            pieces[5].start + pieces[5].length / 2f,
        )
    }

    /** Point at [distance] along the outline, wrapping in both directions. */
    fun pointAt(distance: Float): Offset {
        if (length <= 0f) return Offset.Zero
        val d = distance - floor(distance / length) * length
        val piece = pieces.lastOrNull { it.start <= d } ?: pieces.first()
        val local = (d - piece.start).coerceIn(0f, piece.length)
        val center = piece.arcCenter
        return if (center == null) {
            val t = if (piece.length > 0f) local / piece.length else 0f
            piece.from + (piece.to - piece.from) * t
        } else {
            val angle = piece.startAngle + if (piece.radius > 0f) local / piece.radius else 0f
            center + Offset(cos(angle), sin(angle)) * piece.radius
        }
    }

    /** Appends the clockwise perimeter sub-path from [startDistance] to [endDistance] onto [path]. */
    fun appendSegment(path: Path, startDistance: Float, endDistance: Float) {
        if (length <= 0f || endDistance <= startDistance) return
        var remaining = min(endDistance - startDistance, length)
        val startPt = pointAt(startDistance)
        path.moveTo(startPt.x, startPt.y)

        var d = startDistance - floor(startDistance / length) * length
        var pieceIdx = pieces.indexOfLast { it.length > 1e-4f && d >= it.start - 1e-4f && d < it.start + it.length - 1e-4f }
        if (pieceIdx < 0) {
            d = 0f
            pieceIdx = pieces.indexOfFirst { it.length > 1e-4f }
            if (pieceIdx < 0) return
        }

        val radToDeg = 180f / PI.toFloat()
        var steps = 0
        while (remaining > 1e-4f && steps < 16) {
            steps++
            val piece = pieces[pieceIdx]
            if (piece.length <= 1e-4f) {
                pieceIdx = (pieceIdx + 1) % pieces.size
                d = if (pieceIdx == 0) 0f else pieces[pieceIdx].start
                continue
            }
            val localStart = (d - piece.start).coerceIn(0f, piece.length)
            val avail = piece.length - localStart
            if (avail <= 1e-4f) {
                pieceIdx = (pieceIdx + 1) % pieces.size
                d = if (pieceIdx == 0) 0f else pieces[pieceIdx].start
                continue
            }
            val step = min(remaining, avail)
            val localEnd = localStart + step
            val center = piece.arcCenter
            if (center == null) {
                val pt = piece.from + (piece.to - piece.from) * (localEnd / piece.length)
                path.lineTo(pt.x, pt.y)
            } else if (piece.radius > 0f) {
                val startDeg = (piece.startAngle + localStart / piece.radius) * radToDeg
                val sweepDeg = (step / piece.radius) * radToDeg
                path.arcTo(
                    Rect(center.x - piece.radius, center.y - piece.radius, center.x + piece.radius, center.y + piece.radius),
                    startDeg,
                    sweepDeg,
                    forceMoveTo = false,
                )
            }
            remaining -= step
            if (remaining > 1e-4f) {
                pieceIdx = (pieceIdx + 1) % pieces.size
                d = if (pieceIdx == 0) 0f else pieces[pieceIdx].start
            }
        }
    }

    /**
     * Distance travelled at [lap] (0..1) of a lap that is split into four segments between the
     * middles of consecutive corner arcs. Each segment takes time proportional to its length,
     * so the mean speed is uniform. Within a segment the eased progress makes the head slowest
     * at the corners and fastest mid-edge. The result is relative to the start of the top edge
     * and may be negative before the first segment boundary, so callers wrap it.
     */
    fun distanceAtLap(lap: Float, k: Float = COMET_SLOWDOWN): Float {
        if (length <= 0f) return 0f
        val u = wrap01(lap)
        var elapsed = 0f
        for (i in 0 until 4) {
            val from = cornerMids[i]
            val to = if (i == 3) cornerMids[0] + length else cornerMids[i + 1]
            val segment = to - from
            val share = segment / length
            if (u < elapsed + share || i == 3) {
                val f = if (share > 0f) ((u - elapsed) / share).coerceIn(0f, 1f) else 0f
                return from + segment * easedSegmentProgress(f, k)
            }
            elapsed += share
        }
        return cornerMids[0]
    }

    /** Position of the leading edge at [lap]. */
    fun cometPoint(lap: Float, k: Float = COMET_SLOWDOWN): Offset = pointAt(distanceAtLap(lap, k))
}

/**
 * Samples monotonically non-increasing perimeter distances backward in time over [tailLapSpan] laps,
 * starting with the leading edge at index `0`. Wrapping across the perimeter seam is unwrapped so
 * adjacent entries always form valid `[distances[i + 1], distances[i]]` spans.
 */
internal fun traceSegmentDistances(
    path: RoundedPerimeter,
    headLap: Float,
    tailLapSpan: Float,
    samples: Int,
    k: Float = COMET_SLOWDOWN,
): FloatArray {
    val distances = FloatArray(samples)
    if (samples <= 0) return distances
    distances[0] = path.distanceAtLap(wrap01(headLap), k)
    if (samples == 1 || path.length <= 0f) return distances
    for (i in 1 until samples) {
        val lap = wrap01(headLap - tailLapSpan * i / (samples - 1))
        var raw = path.distanceAtLap(lap, k)
        while (raw > distances[i - 1] + 1e-4f) {
            raw -= path.length
        }
        if (raw > distances[i - 1]) {
            raw = distances[i - 1]
        }
        distances[i] = raw
    }
    return distances
}
