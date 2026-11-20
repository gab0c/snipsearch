package com.snipsearch.ui.overlay

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

private const val SPEED_MEMORY_MILLIS = 140L
private const val HALO_FADE_MILLIS = 240L
private val haloColor = Color(0xFF6AAEFF)

@Composable
internal fun rememberSelectionGlowTrail(vararg keys: Any?): SelectionGlowTrail {
    val trail = remember(*keys) { SelectionGlowTrail() }
    LaunchedEffect(trail) {
        snapshotFlow { trail.lastMovementAt }.collectLatest { lastMovementAt ->
            if (lastMovementAt == null) return@collectLatest
            do {
                val now = withFrameNanos { SystemClock.uptimeMillis() }
                trail.advanceClock(now)
            } while (now - lastMovementAt < HALO_FADE_MILLIS)
        }
    }
    return trail
}

internal class SelectionGlowTrail {
    private var stroke = Path()
    private var lastPoint: Offset? = null
    private val halos = ArrayDeque<Halo>()
    private var lastSampleAt = 0L
    private var motion by mutableStateOf<Motion?>(null)
    private var frameTime by mutableLongStateOf(0L)

    val lastMovementAt: Long? get() = motion?.uptimeMillis

    fun start(point: Offset, uptimeMillis: Long) {
        clear()
        stroke.moveTo(point.x, point.y)
        lastPoint = point
        lastSampleAt = uptimeMillis
    }

    fun extend(point: Offset, uptimeMillis: Long, density: Float) {
        val previous = lastPoint ?: return
        val distance = (point - previous).getDistance()
        if (distance < density) return

        val elapsed = (uptimeMillis - lastSampleAt).coerceAtLeast(1L)
        val speed = distance / density / elapsed
        val previousWeight = (1f - elapsed.toFloat() / SPEED_MEMORY_MILLIS).coerceIn(0f, 0.55f)
        val smoothedSpeed = speed * (1f - previousWeight) + (motion?.speed ?: speed) * previousWeight
        val acceleration = (smoothedSpeed - (motion?.speed ?: smoothedSpeed)) / elapsed
        stroke.lineTo(point.x, point.y)
        lastPoint = point
        lastSampleAt = uptimeMillis
        val radius = haloRadiusDp(acceleration)
        halos.addLast(Halo(point, radius, uptimeMillis))
        motion = Motion(smoothedSpeed, uptimeMillis)
        frameTime = uptimeMillis
    }

    fun advanceClock(uptimeMillis: Long) {
        while (halos.isNotEmpty() && uptimeMillis - halos.first().time >= HALO_FADE_MILLIS) {
            halos.removeFirst()
        }
        frameTime = uptimeMillis
    }

    fun clear() {
        stroke = Path()
        lastPoint = null
        halos.clear()
        motion = null
    }

    fun draw(scope: DrawScope) = with(scope) {
        if (motion == null) return@with
        for (halo in halos) {
            val opacity = haloOpacity(frameTime - halo.time)
            val haloRadius = halo.radiusDp.dp.toPx()
            drawCircle(
                Brush.radialGradient(
                    0f to haloColor.copy(alpha = 0.12f * opacity),
                    0.35f to haloColor.copy(alpha = 0.06f * opacity),
                    1f to haloColor.copy(alpha = 0f),
                    center = halo.point,
                    radius = haloRadius,
                ),
                radius = haloRadius,
                center = halo.point,
            )
        }
        drawPath(
            stroke,
            Color.Black.copy(alpha = 0.4f),
            style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            stroke,
            Color.White,
            style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    private data class Halo(val point: Offset, val radiusDp: Float, val time: Long)
    private data class Motion(val speed: Float, val uptimeMillis: Long)
}

internal fun haloRadiusDp(accelerationDpPerMillisecondSquared: Float): Float {
    val acceleration = (accelerationDpPerMillisecondSquared / 0.02f).coerceIn(0f, 1f)
    return 48f - 26f * acceleration
}

internal fun haloOpacity(idleMillis: Long): Float {
    val progress = (idleMillis.toFloat() / HALO_FADE_MILLIS).coerceIn(0f, 1f)
    return 1f - progress * progress * (3f - 2f * progress)
}
