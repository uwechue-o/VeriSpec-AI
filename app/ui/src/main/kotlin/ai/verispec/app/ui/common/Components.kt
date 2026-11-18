package ai.verispec.app.ui.common

import ai.verispec.app.ui.theme.StatusColors
import ai.verispec.core.domain.model.CoverageStatus
import ai.verispec.core.domain.model.ThermalStatus
import ai.verispec.core.domain.model.Workload
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun KeyValueRow(key: String, value: String, mono: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

fun CoverageStatus.color(): Color = when (this) {
    CoverageStatus.SATISFIED -> StatusColors.satisfied
    CoverageStatus.PARTIAL -> StatusColors.partial
    CoverageStatus.NOT_COVERED -> StatusColors.notCovered
}

fun ThermalStatus.color(): Color = when (this) {
    ThermalStatus.NONE -> StatusColors.satisfied
    ThermalStatus.LIGHT -> Color(0xFF7CB342)
    ThermalStatus.MODERATE -> StatusColors.partial
    ThermalStatus.SEVERE -> Color(0xFFEF6C00)
    ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN -> StatusColors.notCovered
    ThermalStatus.UNKNOWN -> StatusColors.neutral
}

/** Unit of decodeTokensPerSecond: tokens for text generation, forward passes for tensor inference. */
fun Workload.throughputUnit(): String = when (this) {
    is Workload.TextGeneration -> "tok/s"
    is Workload.TensorInference -> "inf/s"
}

/** A point on the decay chart: x = iteration index, y = decode throughput, throttled colours the marker. */
data class ChartPoint(val x: Float, val y: Float, val throttled: Boolean)

/** A dashed horizontal marker, e.g. the envelope's peak or steady state. */
data class ReferenceLine(val y: Double, val label: String)

/**
 * Throughput per iteration, with the envelope's peak and steady state marked
 * when known. Throttled iterations are drawn as larger red markers.
 */
@Composable
fun DecayChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    label: String = "decode tok/s",
    peak: Double? = null,
    steadyState: Double? = null,
) {
    LineChart(
        points = points,
        yUnit = label,
        xLabel = "iteration",
        modifier = modifier,
        integerX = true,
        references = listOfNotNull(
            peak?.let { ReferenceLine(it, "peak %.1f".format(it)) },
            steadyState?.let { ReferenceLine(it, "steady %.1f".format(it)) },
        ),
    )
}

/** Above this many points the markers would merge into a smear, so only the line is drawn. */
private const val MARKER_LIMIT = 60

/**
 * Compose-native line chart with labelled axes. No library, no animation: it
 * has to draw live while a run is measuring, and stay cheap at 3,000 points.
 * [yFromZero] keeps 0 on the axis so a drop reads in proportion; turn it off
 * for quantities like temperature where only the change is informative.
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    yUnit: String,
    xLabel: String,
    modifier: Modifier = Modifier,
    yFromZero: Boolean = true,
    integerX: Boolean = false,
    references: List<ReferenceLine> = emptyList(),
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val referenceColor = MaterialTheme.colorScheme.tertiary
    val background = MaterialTheme.colorScheme.background.copy(alpha = 0.85f)
    Column(modifier) {
        Text(yUnit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
            val ys = points.map { it.y.toDouble() } + references.map { it.y }
            val yScale = AxisScale.of(ys.minOrNull() ?: 0.0, ys.maxOrNull() ?: 1.0, fromZero = yFromZero)
            val xs = points.map { it.x.toDouble() }
            val xScale = AxisScale.of(xs.minOrNull() ?: 0.0, xs.maxOrNull() ?: 1.0, fromZero = true, targetTicks = 5, minStep = if (integerX) 1.0 else 0.0)

            val yLabels = yScale.ticks.map { measurer.measure(yScale.label(it), labelStyle) }
            val xLabels = xScale.ticks.map { measurer.measure(xScale.label(it), labelStyle) }
            val gap = 4.dp.toPx()
            val left = (yLabels.maxOfOrNull { it.size.width } ?: 0) + gap * 2
            val right = (xLabels.lastOrNull()?.size?.width ?: 0) / 2f + gap
            val top = gap * 2
            val bottom = size.height - (xLabels.firstOrNull()?.size?.height ?: 0) - gap * 2
            val plotWidth = size.width - left - right
            val plotHeight = bottom - top
            fun xPx(x: Double) = left + (xScale.fraction(x) * plotWidth).toFloat()
            fun yPx(y: Double) = bottom - (yScale.fraction(y) * plotHeight).toFloat()

            yScale.ticks.forEachIndexed { i, t ->
                val y = yPx(t)
                if (i > 0) drawLine(grid, Offset(left, y), Offset(left + plotWidth, y), strokeWidth = 1f)
                drawText(yLabels[i], topLeft = Offset(left - gap - yLabels[i].size.width, y - yLabels[i].size.height / 2f))
            }
            xScale.ticks.forEachIndexed { i, t ->
                val x = xPx(t)
                drawLine(axisColor, Offset(x, bottom), Offset(x, bottom + gap), strokeWidth = 1.5f)
                drawText(xLabels[i], topLeft = Offset(x - xLabels[i].size.width / 2f, bottom + gap))
            }
            drawLine(axisColor, Offset(left, top), Offset(left, bottom), strokeWidth = 1.5f)
            drawLine(axisColor, Offset(left, bottom), Offset(left + plotWidth, bottom), strokeWidth = 1.5f)

            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            val highest = references.maxOfOrNull { it.y }
            references.forEach { r ->
                val y = yPx(r.y)
                drawLine(referenceColor, Offset(left, y), Offset(left + plotWidth, y), strokeWidth = 2f, pathEffect = dash)
                val text = measurer.measure(r.label, labelStyle.copy(color = referenceColor))
                // Highest label above its line, the rest below, so close values (peak vs steady) never overprint.
                val labelTop = if (r.y == highest) y - text.size.height - 2f else y + 2f
                drawRect(background, Offset(left + gap - 2f, labelTop), Size(text.size.width + 4f, text.size.height.toFloat()))
                drawText(text, topLeft = Offset(left + gap, labelTop))
            }

            if (points.size < 2) return@Canvas
            val path = Path()
            points.forEachIndexed { i, p ->
                val o = Offset(xPx(p.x.toDouble()), yPx(p.y.toDouble()))
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, color, style = Stroke(width = if (points.size > MARKER_LIMIT) 2f else 3f))
            points.forEach { p ->
                // Throttled points are evidence, so they stay visible however long the run.
                if (p.throttled || points.size <= MARKER_LIMIT) {
                    drawCircle(
                        if (p.throttled) StatusColors.notCovered else color,
                        radius = if (p.throttled) 6f else 3.5f,
                        center = Offset(xPx(p.x.toDouble()), yPx(p.y.toDouble())),
                    )
                }
            }
        }
        Text(xLabel, style = labelStyle, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
