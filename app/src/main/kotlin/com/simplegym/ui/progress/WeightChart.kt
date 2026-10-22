package com.simplegym.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplegym.engine.formatKg
import com.simplegym.ui.theme.Accent
import com.simplegym.ui.theme.Amber
import java.time.format.DateTimeFormatter

private val AxisWidth = 48.dp
private val SideInset = 14.dp
private val TopMargin = 28.dp
private val BottomMargin = 28.dp
private val LabelGap = 30.dp
private val DotRadius = 5.dp
private val HurtRadius = 9.dp
private const val FADED_ALPHA = 0.35f
private val DateFormat = DateTimeFormatter.ofPattern("d MMM")

/**
 * Working weight per session as a line, drawn straight from the points so an assisted exercise (negative weights) rises
 * as the assistance drops. Each point is labelled with its best reps (thinned when they would collide); faded points
 * (deload, flush, return) are drawn at 35% alpha and a session with a hurt flag gets an amber ring. Sessions are spaced
 * evenly, newest on the right. Needs at least one point.
 */
@Composable
fun WeightChart(points: List<ChartPoint>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = if (points.isEmpty()) "No sessions" else
        "Weight chart, ${points.size} sessions, latest ${formatKg(points.last().weightKg)} kg for ${points.last().reps} reps"
    Canvas(modifier.testTag("weight-chart").semantics { contentDescription = description }) {
        if (points.isEmpty()) return@Canvas
        val axisStyle = TextStyle(color = muted, fontSize = 12.sp)
        val repsStyle = TextStyle(color = labelColor, fontSize = 13.sp)

        val ticks = axisTicks(points.minOf { it.weightKg }, points.maxOf { it.weightKg })
        val low = ticks.first()
        val span = ticks.last() - low
        val left = AxisWidth.toPx() + SideInset.toPx()
        val right = size.width - SideInset.toPx()
        val top = TopMargin.toPx()
        val bottom = size.height - BottomMargin.toPx()
        fun yOf(weight: Double) = bottom - ((weight - low) / span).toFloat() * (bottom - top)
        fun xOf(index: Int) = if (points.size == 1) (left + right) / 2 else left + index * (right - left) / (points.size - 1)

        ticks.forEach { tick ->
            val y = yOf(tick)
            drawLine(grid, Offset(AxisWidth.toPx(), y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(formatKg(tick), axisStyle)
            drawText(text, topLeft = Offset(AxisWidth.toPx() - 6.dp.toPx() - text.size.width, y - text.size.height / 2f))
        }

        val line = Path()
        points.forEachIndexed { i, p -> if (i == 0) line.moveTo(xOf(i), yOf(p.weightKg)) else line.lineTo(xOf(i), yOf(p.weightKg)) }
        drawPath(line, Accent.copy(alpha = 0.6f), style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

        val labelled = labelledIndices(points.size, right - left, LabelGap.toPx()).toSet()
        points.forEachIndexed { i, p ->
            val centre = Offset(xOf(i), yOf(p.weightKg))
            val alpha = if (p.faded) FADED_ALPHA else 1f
            drawCircle(Accent.copy(alpha = alpha), DotRadius.toPx(), centre)
            if (p.hurt) drawCircle(Amber, HurtRadius.toPx(), centre, style = Stroke(width = 2.5.dp.toPx()))
            if (i in labelled) {
                val text = measurer.measure(p.reps.toString(), repsStyle.copy(color = labelColor.copy(alpha = alpha)))
                drawCentredAbove(text, centre, if (p.hurt) HurtRadius.toPx() else DotRadius.toPx())
            }
        }

        dateLabel(measurer, points.first(), xOf(0), bottom, axisStyle)
        if (points.size > 1) dateLabel(measurer, points.last(), xOf(points.size - 1), bottom, axisStyle)
    }
}

private fun DrawScope.drawCentredAbove(text: TextLayoutResult, centre: Offset, radius: Float) {
    val x = (centre.x - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
    drawText(text, topLeft = Offset(x, centre.y - radius - 2.dp.toPx() - text.size.height))
}

private fun DrawScope.dateLabel(
    measurer: TextMeasurer,
    point: ChartPoint,
    x: Float,
    bottom: Float,
    style: TextStyle,
) {
    val text = measurer.measure(DateFormat.format(point.date), style)
    val left = (x - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
    drawText(text, topLeft = Offset(left, bottom + 8.dp.toPx()))
}
