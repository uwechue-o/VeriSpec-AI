package ai.verispec.tools.report.docs

import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.evidence.BundleCodec
import org.knowm.xchart.AnnotationText
import org.knowm.xchart.AnnotationLine
import org.knowm.xchart.style.Styler
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.io.File
import javax.imageio.ImageIO

/**
 * The chart images shown at the top of README.md, written to DOCS/readme. Like the
 * manuals' charts they are drawn from the signed bundles kept under resources/docs, so
 * the README shows what the evidence says rather than pasted numbers:
 *
 * - the long MediaPipe run of Qwen2.5-0.5B on the Pixel 6 (2026-11-16), decode speed
 *   and battery temperature over 70 minutes;
 * - the first cross-device run (2026-11-14), from [CrossDeviceFigures];
 * - the app's own Live Run screen during the long run, copied from [AppScreenFigures].
 */
object ReadmeFigures {

    private const val LONG_RUN = "/docs/long-run-2026-11-16/pixel6-mediapipe-qwen-2026-11-16.json"
    private const val LONG_RUN_MANIFEST = "5c8d8b52"

    val longRun: EvidenceBundle by lazy {
        val text = ReadmeFigures::class.java.getResource(LONG_RUN)?.readText() ?: error("missing bundle resource $LONG_RUN")
        BundleCodec.decode(text).also {
            check(it.manifest.manifestId.value.startsWith(LONG_RUN_MANIFEST)) { "unexpected long-run manifest ${it.manifest.manifestId}" }
        }
    }

    /** Decode tok/s per iteration and battery temperature, both against minutes into the run. */
    fun longRunChart(): java.awt.image.BufferedImage {
        val obs = longRun.observations
        val minutes = obs.runDurationMillis / 60_000.0
        val chart = CrossDeviceFigures.base(
            "Minutes into the run", "Tokens per second",
            xMax = kotlin.math.ceil(minutes / 10) * 10, xLabelEvery = 10.0,
        )
        chart.styler.yAxisMin = 0.0
        chart.styler.yAxisMax = 30.0
        CrossDeviceFigures.line(
            chart, "Decode speed, Qwen2.5-0.5B on MediaPipe (Pixel 6)",
            obs.iterations.map { it.elapsedMillis / 60_000.0 }, obs.iterations.map { it.decodeTokensPerSecond },
            CrossDeviceFigures.PIXEL,
        )

        val temps = obs.powerTimeline.mapNotNull { s -> s.batteryTemperatureDeciC?.let { s.elapsedMillis / 60_000.0 to it / 10.0 } }
        CrossDeviceFigures.line(chart, "Battery temperature (°C, right axis)", temps.map { it.first }, temps.map { it.second }, TEMPERATURE)
            .yAxisGroup = 1
        chart.setYAxisGroupTitle(1, "Battery temperature (°C)")
        chart.styler.setYAxisGroupPosition(1, Styler.YAxisPosition.Right)
        chart.styler.setYAxisMin(1, 20.0)
        chart.styler.setYAxisMax(1, 50.0)

        // The screen timed out partway through (see CLAUDE.md); mark where, so the later drop is read correctly.
        obs.powerTimeline.firstOrNull { it.screenOn == false }?.let { off ->
            val x = off.elapsedMillis / 60_000.0
            chart.addAnnotation(AnnotationLine(x, true, false))
            chart.addAnnotation(AnnotationText("screen timed out", x - 5.0, 3.0, false))
            chart.styler.apply {
                annotationLineColor = TEXT_SECONDARY
                annotationLineStroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(8f, 8f), 0f)
                annotationTextFont = Font(Font.SANS_SERIF, Font.PLAIN, 22)
                annotationTextFontColor = TEXT_SECONDARY
            }
        }
        return CrossDeviceFigures.render(chart)
    }

    private fun write(image: java.awt.image.BufferedImage, file: File) {
        ImageIO.write(image, "png", file)
        println("Wrote ${file.path} (${file.length()} bytes)")
    }

    private val TEMPERATURE = Color(0xc2, 0x3b, 0x22)
    private val TEXT_SECONDARY = Color(0x52, 0x51, 0x4e)

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: error("usage: ReadmeFigures <output dir>")).apply { mkdirs() }
        write(longRunChart(), File(dir, "pixel6-llm-long-run.png"))
        write(CrossDeviceFigures.throughput(), File(dir, "cross-device-throughput.png"))
        write(AppScreenFigures.liveRun, File(dir, "app-live-run.png"))
    }
}
