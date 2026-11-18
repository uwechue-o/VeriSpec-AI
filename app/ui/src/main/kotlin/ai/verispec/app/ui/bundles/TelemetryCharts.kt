package ai.verispec.app.ui.bundles

import ai.verispec.app.ui.common.ChartPoint
import ai.verispec.app.ui.common.LineChart
import ai.verispec.core.domain.model.Observations
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One recorded signal over the run, x in seconds since run start. Every series
 * shares that time base, so a clock drop and a temperature rise line up.
 */
data class TelemetrySeries(
    val title: String,
    val unit: String,
    val points: List<ChartPoint>,
    /** False for temperatures, where the change matters more than the distance from 0. */
    val fromZero: Boolean,
) {
    companion object {
        /** The signals worth a chart; one that was never readable is left out rather than drawn flat. */
        fun of(o: Observations): List<TelemetrySeries> = listOfNotNull(
            series("Fastest core clock", "MHz", fromZero = true, o.iterations.mapNotNull { r ->
                r.clocks?.cpuKhz?.maxOrNull()?.let { ChartPoint(seconds(r.elapsedMillis), it / 1_000f, r.wasThrottled) }
            }),
            series("GPU clock", "MHz", fromZero = true, o.iterations.mapNotNull { r ->
                r.clocks?.gpuKhz?.let { ChartPoint(seconds(r.elapsedMillis), it / 1_000f, r.wasThrottled) }
            }),
            series("Battery temperature", "°C", fromZero = false, o.powerTimeline.mapNotNull { s ->
                s.batteryTemperatureDeciC?.let { ChartPoint(seconds(s.elapsedMillis), it / 10f, false) }
            }),
            series("Thermal headroom", "0 = cool, 1 = severe", fromZero = true, o.thermalTimeline.mapNotNull { s ->
                s.headroom?.let { ChartPoint(seconds(s.elapsedMillis), it, s.status.isThrottling) }
            }),
        )

        private fun series(title: String, unit: String, fromZero: Boolean, points: List<ChartPoint>) =
            if (points.size < 2) null else TelemetrySeries(title, unit, points, fromZero)

        private fun seconds(millis: Long) = millis / 1_000f
    }
}

/** The hardware telemetry drawn against time, one small chart per readable signal. */
@Composable
fun TelemetryCharts(observations: Observations, modifier: Modifier = Modifier) {
    val series = TelemetrySeries.of(observations)
    if (series.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        series.forEach { s ->
            Column {
                Text(s.title, style = MaterialTheme.typography.labelLarge)
                LineChart(points = s.points, yUnit = s.unit, xLabel = "seconds since run start", yFromZero = s.fromZero)
            }
        }
    }
}
