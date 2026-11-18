package ai.verispec.tools.report

import ai.verispec.core.domain.model.EvidenceBundle
import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.XYChartBuilder
import org.knowm.xchart.XYSeries
import org.knowm.xchart.style.Styler
import org.knowm.xchart.style.markers.SeriesMarkers
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Server-side chart rendering for the PDF. Headless: no device, no Compose. */
object ChartRenderer {

    /** Throughput against iteration index, with throttled iterations as a second series. */
    fun decayChart(bundle: EvidenceBundle): BufferedImage? {
        val iterations = bundle.observations.iterations
        if (iterations.size < 2) return null

        val chart = XYChartBuilder()
            .width(CHART_WIDTH)
            .height(CHART_HEIGHT)
            .title("Decode throughput under sustained load")
            .xAxisTitle("Iteration")
            .yAxisTitle("tokens / s")
            .build()

        chart.styler.apply {
            legendPosition = Styler.LegendPosition.InsideNE
            isPlotGridVerticalLinesVisible = false
            chartBackgroundColor = Color.WHITE
            plotBackgroundColor = Color.WHITE
            seriesColors = arrayOf(LINE, THROTTLED)
        }

        val x = iterations.map { it.index.toDouble() }
        val y = iterations.map { it.decodeTokensPerSecond }
        chart.addSeries("throughput", x, y).apply {
            marker = SeriesMarkers.NONE
            xySeriesRenderStyle = XYSeries.XYSeriesRenderStyle.Line
        }

        val throttled = iterations.filter { it.wasThrottled }
        if (throttled.isNotEmpty()) {
            chart.addSeries(
                "throttled",
                throttled.map { it.index.toDouble() },
                throttled.map { it.decodeTokensPerSecond },
            ).apply {
                marker = SeriesMarkers.CIRCLE
                xySeriesRenderStyle = XYSeries.XYSeriesRenderStyle.Scatter
            }
        }

        return BitmapEncoder.getBufferedImage(chart)
    }

    fun toPngBytes(image: BufferedImage): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private const val CHART_WIDTH = 920
    private const val CHART_HEIGHT = 400
    private val LINE = Color(0x12, 0x33, 0x3B)
    private val THROTTLED = Color(0xC6, 0x28, 0x28)
}
