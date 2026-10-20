package io.github.qaisarpipeshift.utter.ime.panel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Number of bars around the ring (docs/plan.md Task 9). */
private const val BARS = 32

/**
 * The waveform ring around the mic (docs/spec.md §9, docs/plan.md Task 9).
 *
 * Why this exists only while recording: the ring is the one moving thing in the panel, and the battery rule says
 * nothing animates while the keyboard is idle. The last [BARS] amplitudes are passed in already trimmed by the
 * caller; each bar eases to its new level in about a tenth of a second, so a 10 Hz amplitude stream looks like
 * speech instead of a flickering bar chart.
 */
@Composable
fun Waveform(amplitudes: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.secondary
    val levels = ArrayList<State<Float>>(BARS)
    for (index in 0 until BARS) {
        val target = amplitudes.getOrElse(index) { 0f }.coerceIn(0f, 1f)
        levels.add(
            animateFloatAsState(
                targetValue = target,
                animationSpec = tween(durationMillis = 110),
                label = "utter_wave_$index",
            ),
        )
    }
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val base = size.minDimension * 0.40f
        val barWidth = 2.dp.toPx()
        val maxBar = size.minDimension * 0.085f
        val startGap = 1.5.dp.toPx()
        for (index in 0 until BARS) {
            val degrees = (index.toFloat() / BARS) * 360f - 90f
            val radians = Math.toRadians(degrees.toDouble())
            val directionX = cos(radians).toFloat()
            val directionY = sin(radians).toFloat()
            val inner = base
            val outer = base + startGap + maxBar * levels[index].value
            drawLine(
                color = color,
                start = Offset(center.x + directionX * inner, center.y + directionY * inner),
                end = Offset(center.x + directionX * outer, center.y + directionY * outer),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
