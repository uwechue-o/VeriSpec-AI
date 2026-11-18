package ai.verispec.tools.report.docs

import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.evidence.BundleCodec
import ai.verispec.tools.analysis.ComputeDecayUseCase
import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.XYChart
import org.knowm.xchart.XYChartBuilder
import org.knowm.xchart.XYSeries
import org.knowm.xchart.style.Styler
import org.knowm.xchart.style.markers.SeriesMarkers
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * Charts and section text for the first cross-device run (2026-11-14), drawn from the
 * signed bundles kept under resources/docs/cross-device-2026-11-14 so the manuals are
 * regenerated from the evidence itself rather than from pasted numbers.
 *
 * Colours: categorical slots in fixed order, one per device, the same in every chart:
 * Pixel blue, Honor orange, Samsung aqua.
 */
object CrossDeviceFigures {

    private const val DIR = "/docs/cross-device-2026-11-14/"

    /** One device's run: its bundle, its label in charts and tables, its colour. */
    class Run(val bundle: EvidenceBundle, val label: String, val shortLabel: String, val colour: Color) {
        val throughput: List<Double> get() = bundle.observations.iterations.map { it.decodeTokensPerSecond }
        val median: Double get() = median(throughput)

        /** Change from the median of the first 50 iterations to the last 50, in percent (negative = slower). */
        val drift: Double get() = (median(throughput.takeLast(50)) / median(throughput.take(50)) - 1) * 100
        val medianLatencyMillis: Double get() = median(bundle.observations.iterations.map { it.totalMicros / 1_000.0 })
        val fastestCoreMhz: List<Long> get() =
            bundle.observations.iterations.mapNotNull { it.clocks?.cpuKhz?.maxOrNull()?.div(1_000) }
        val peakPssMb: Long get() = bundle.observations.memoryPeaks.peakPssKb / 1_024
        val gateWaitMinutes: Double? get() = bundle.observations.preconditions?.waitedMillis?.div(60_000.0)
    }

    val pixel by lazy { Run(load("pixel6-2026-11-14.json"), "Pixel 6 (Tensor)", "Pixel 6", PIXEL) }
    val honor by lazy { Run(load("honor-magic-v3-2026-11-14.json"), "Honor Magic V3 (Snapdragon 8 Gen 3)", "Honor Magic V3", HONOR) }
    val samsung by lazy { Run(load("samsung-tab-s7plus-2026-11-14.json"), "Galaxy Tab S7+ (Snapdragon 865+)", "Galaxy Tab S7+", SAMSUNG) }

    /** The Pixel's run of the same manifest two days earlier: the run-to-run baseline. */
    val pixelEarlier: EvidenceBundle by lazy { load("pixel6-2026-11-12.json") }

    /** The tablet's second run, started after it had cooled: its run-to-run baseline. */
    val samsungRepeat by lazy {
        Run(load("samsung-tab-s7plus-2026-11-14-repeat.json"), "Galaxy Tab S7+, second run", "Galaxy Tab S7+ (2nd)", SAMSUNG)
    }

    private val runs get() = listOf(pixel, honor, samsung)

    private fun load(name: String): EvidenceBundle {
        val text = CrossDeviceFigures::class.java.getResource(DIR + name)?.readText()
            ?: error("missing bundle resource $DIR$name")
        return BundleCodec.decode(text)
    }

    /** The figures only mean something if every bundle ran the same manifest. */
    private fun checkSameManifest() {
        val ids = (runs.map { it.bundle } + pixelEarlier + samsungRepeat.bundle).map { it.manifest.manifestId }.toSet()
        check(ids.size == 1) { "cross-device figures need one manifest, found $ids" }
    }

    /* ------------------------------------------------------------------ charts */

    /** Inferences per second for every iteration on every device. */
    fun throughput(): BufferedImage {
        checkSameManifest()
        val chart = base("Iteration", "Inferences per second")
        chart.styler.yAxisMin = 0.0
        chart.styler.yAxisMax = 50.0
        runs.forEach { r -> line(chart, r.label, r.bundle.observations.iterations.map { it.index.toDouble() }, r.throughput, r.colour) }
        return render(chart)
    }

    /** The fastest core's clock after every iteration: it shows how each device manages its CPU. */
    fun fastestCoreClock(): BufferedImage {
        checkSameManifest()
        val chart = base("Iteration", "Fastest core clock (MHz)")
        chart.styler.yAxisMin = 0.0
        chart.styler.yAxisMax = 3500.0
        runs.forEach { r ->
            val points = r.bundle.observations.iterations.mapNotNull { it.clocks?.cpuKhz?.maxOrNull()?.let { khz -> it.index.toDouble() to khz / 1_000.0 } }
            line(chart, r.label, points.map { it.first }, points.map { it.second }, r.colour)
        }
        return render(chart)
    }

    /**
     * Largest absolute difference from the Pixel across the 64-value logit projection,
     * per iteration, in units of 1e-8. One line per other device, in that device's colour.
     */
    fun outputDifference(): BufferedImage {
        checkSameManifest()
        val chart = base("Iteration", "Largest difference from Pixel 6 (× 10⁻⁸)")
        chart.styler.yAxisMin = 0.0
        listOf(honor, samsung).forEach { r ->
            val delta = maxDelta(pixel.bundle, r.bundle)
            line(chart, "${r.shortLabel} vs Pixel 6", delta.keys.map { it.toDouble() }, delta.values.map { it * 1e8 }, r.colour)
        }
        return render(chart)
    }

    /**
     * The tablet's hottest big-core zone (cpu-1-*-usr) through each of its two runs, from the
     * per-second thermal timeline. Only the tablet exposes zones; the Android 17 phones deny them.
     * Same device, so same colour; the second run is dashed and marked.
     */
    fun tabletCoreTemperature(): BufferedImage {
        checkSameManifest()
        val seconds = listOf(samsung, samsungRepeat).maxOf { it.bundle.observations.runDurationMillis } / 1_000.0
        val chart = base("Seconds into the run", "Hottest big core (°C)", xMax = kotlin.math.ceil(seconds), xLabelEvery = 2.0)
        chart.styler.yAxisMin = 20.0
        chart.styler.yAxisMax = 80.0
        listOf(samsung to false, samsungRepeat to true).forEach { (r, dashed) ->
            val points = bigCoreTemperatures(r)
            line(
                chart, if (dashed) "Galaxy Tab S7+, second run (after cooling)" else "Galaxy Tab S7+, first run",
                points.map { it.first }, points.map { it.second }, r.colour, dashed = dashed, markers = true,
            )
        }
        return render(chart)
    }

    /** (seconds, hottest cpu-1-*-usr zone in °C) for every thermal sample that carries zones. */
    private fun bigCoreTemperatures(r: Run): List<Pair<Double, Double>> =
        r.bundle.observations.thermalTimeline.mapNotNull { s ->
            s.zoneTemperaturesCelsius.filterKeys { it.startsWith("cpu-1-") && it.endsWith("-usr") }.values.maxOrNull()
                ?.let { s.elapsedMillis / 1_000.0 to it.toDouble() }
        }

    /* ------------------------------------------------------------------ numbers */

    private fun digestAgreement(reference: EvidenceBundle, other: EvidenceBundle): Double {
        val digests = reference.observations.outputDigests.associate { it.iterationIndex to it.sha256 }
        val others = other.observations.outputDigests
        return others.count { digests[it.iterationIndex] == it.sha256 }.toDouble() / others.size
    }

    private fun steady(r: Run): String =
        ComputeDecayUseCase()(r.bundle)?.steadyStateTokensPerSecond?.let { "%.1f inf/s".format(it) } ?: "not settled"

    /** First reading and peak of the hottest big core: the rise, not wherever the run happened to stop. */
    private fun coreTempRange(r: Run): Pair<Double, Double> = bigCoreTemperatures(r).let { it.first().second to it.maxOf { p -> p.second } }

    private val minutesBetweenTabletRuns: Long
        get() = (samsungRepeat.bundle.observations.runStartedAtEpochMillis - samsung.bundle.observations.runStartedAtEpochMillis) / 60_000

    fun maxDeltaFromPixel(r: Run): Double = maxDelta(pixel.bundle, r.bundle).values.max()

    /** Share of iterations whose full-output digest matches the Pixel's. */
    fun digestAgreementWithPixel(other: EvidenceBundle): Double = digestAgreement(pixel.bundle, other)

    private fun maxDelta(a: EvidenceBundle, b: EvidenceBundle): Map<Int, Double> {
        val other = b.observations.outputDigests.associateBy { it.iterationIndex }
        return a.observations.outputDigests.mapNotNull { d ->
            val x = d.logitProjection ?: return@mapNotNull null
            val y = other[d.iterationIndex]?.logitProjection ?: return@mapNotNull null
            d.iterationIndex to x.indices.maxOf { abs(x[it].toDouble() - y[it].toDouble()) }
        }.toMap().toSortedMap()
    }

    private fun median(values: List<Double>): Double = values.sorted().let { it[it.size / 2] }

    private fun pct(v: Double) = "%+.1f %%".format(v)

    private fun plainDrift(v: Double) =
        if (v <= -1.0) "%.1f %% slower over the last 50 repetitions than the first 50".format(-v)
        else "held its speed from the first 50 repetitions to the last 50"

    private fun clockRange(r: Run): String =
        r.fastestCoreMhz.let { if (it.min() == it.max()) "${it.min()} MHz, fixed" else "${it.min()}-${it.max()} MHz" }

    private fun agreement(r: Run): String = when (r) {
        pixel -> "reference"
        else -> if (maxDeltaFromPixel(r) == 0.0) "%.0f %% of digests equal to the Pixel's, projections identical".format(digestAgreementWithPixel(r.bundle) * 100)
        else "%.0f %% of digests equal to the Pixel's, max projection difference %.1fe-8".format(digestAgreementWithPixel(r.bundle) * 100, maxDeltaFromPixel(r) * 1e8)
    }

    /* ------------------------------------------------------------------ sections */

    /** The comparison as the plain-language editions tell it. */
    fun plainSection(doc: DocPdf) {
        doc.h2("The first cross-device comparison")
        doc.p(
            "On 14 November 2026 three devices with three different chips ran the identical test: a Pixel 6 phone, an " +
                "Honor Magic V3 phone and a Samsung Galaxy Tab S7+ tablet, each running the same image-recognition model " +
                "on the same 300 inputs with the same settings, unplugged and with the screen on and dimmed. The table " +
                "and charts below are drawn straight from the sealed results.",
        )
        doc.p(
            "No one drew these charts by hand or in a spreadsheet. The toolkit's own report software reads the sealed " +
                "result files and plots them with XChart, a free, open-source charting library for Java, every time this " +
                "manual is produced, so the pictures always show exactly what the devices recorded. Each device keeps " +
                "the same colour in every chart, and the colours were checked to stay distinguishable for readers with " +
                "colour-blindness.",
        )
        doc.table(
            "Device" to "What it recorded",
            runs.map { r ->
                r.label to (
                    "Middle speed %.1f inferences per second; %s. ".format(r.median, plainDrift(r.drift)) +
                        when (r) {
                            pixel -> "Its answers are the reference for the others."
                            else -> if (digestAgreementWithPixel(r.bundle) == 1.0) "Answers identical to the Pixel's on every repetition."
                            else "Answers never identical to the Pixel's, but at most %.1f hundred-millionths apart.".format(maxDeltaFromPixel(r) * 1e8)
                        }
                    )
            },
            leftFraction = 0.40f,
        )
        doc.figure(
            throughput(),
            "Speed on each repetition. The Samsung tablet is fastest, while the Pixel starts close behind and slows as " +
                "it works, and the Honor is the slowest but almost perfectly flat.",
        )
        doc.figure(
            fastestCoreClock(),
            "How fast each device's quickest processor core was running after each repetition. The tablet and the " +
                "Honor each held one speed for the whole test (%d and %d MHz) while the Pixel's moved around, and the "
                    .format(samsung.fastestCoreMhz.first(), honor.fastestCoreMhz.first()) +
                "result records the speed, not the reason for it.",
        )
        doc.figure(
            outputDifference(),
            "How far each device's answers were from the Pixel's, using the 64 summary numbers kept for each answer. " +
                "The tablet's answers matched the Pixel's exactly on all 300 repetitions, which is the line at zero, and " +
                "so did the Pixel's own answers from two days earlier (not drawn, since that line also sits at zero). " +
                "The Honor's answers were never identical, but the largest gap was %.1f hundred-millionths."
                    .format(maxDeltaFromPixel(honor) * 1e8),
        )
        doc.p(
            "So two devices agree exactly and one differs very slightly. The Pixel and the tablet use processor " +
                "designs from the same generation while the Honor's are several generations newer, and the likeliest " +
                "explanation, not yet tested, is that the newer design lets the software take a slightly different " +
                "arithmetic route to the same answer. Three devices and one model are a first look, not a general rule.",
        )
        val (firstStart, firstEnd) = coreTempRange(samsung)
        val (repeatStart, repeatEnd) = coreTempRange(samsungRepeat)
        doc.p(
            "Each device also repeats itself: the Pixel, run again two days apart, and the tablet, run again %d minutes "
                .format(minutesBetweenTabletRuns) +
                "later once it had cooled, gave answers identical to their first runs on every repetition. The tablet's " +
                "speed barely moved either, with a middle value of %.1f inferences per second the first time and %.1f the second."
                    .format(samsung.median, samsungRepeat.median),
        )
        doc.figure(
            tabletCoreTemperature(),
            "The tablet's hottest processor core during each run, read from its own temperature sensors (the two " +
                "phones don't let apps read theirs). It heated from %.0f °C to a peak of %.0f °C the first time and from %.0f °C "
                    .format(firstStart, firstEnd, repeatStart) +
                "to %.0f °C the second, most of that in the first second, while its clock speed never changed. Its speed still fell by about "
                    .format(repeatEnd) +
                "%.0f %% over each run, so something other than the clock slows as the chip warms; memory is one "
                    .format(-(samsung.drift + samsungRepeat.drift) / 2) +
                "possibility, though this test doesn't check it.",
        )
    }

    /** The comparison as the developer editions tell it. */
    fun developerSection(doc: DocPdf) {
        doc.h2("The first cross-device run")
        doc.p(
            "Manifest ${pixel.bundle.manifest.manifestId.value.take(8)}... on three SoCs: EfficientNet-Lite0 FP32, " +
                "LiteRT CPU delegate with 4 threads, 300 iterations, seed 42, ON_DIMMED, margin 0.35, all unplugged. " +
                runs.joinToString("; ") { r ->
                    "${r.shortLabel}: ${r.bundle.device.soc.model}, Android ${r.bundle.device.androidRelease}"
                } +
                ". The charts are rendered from the signed bundles in " +
                "tools/report/src/main/resources/docs/cross-device-2026-11-14, so regenerating the manual redraws them " +
                "from the evidence, and generation fails if the bundles' manifests differ.",
        )
        doc.p(
            "How the charts are made: CrossDeviceFigures decodes each bundle with BundleCodec.decode, builds an XYChart " +
                "with XChart 3.8.8 (org.knowm.xchart, Apache-2.0), and renders it headless at 1600 x 720 px through " +
                "BitmapEncoder.getBufferedImage. DocPdf.figure then embeds the image losslessly with PDFBox's " +
                "LosslessFactory, all inside ./gradlew :tools:report:generateDocs, so no chart exists outside the build. " +
                "Series colours are fixed per device (blue, orange, aqua) and were validated for colour-vision-deficiency " +
                "separation; the aqua is below 3:1 contrast on white, which is why every chart has a table or caption " +
                "carrying the same numbers.",
        )
        doc.table(
            "Device" to "Measured",
            runs.map { r ->
                r.label to (
                    "median %.1f inf/s, first-50 to last-50 %s, median latency %.1f ms; fastest core %s; peak PSS %d MB; gate wait %.1f min; %s."
                        .format(r.median, pct(r.drift), r.medianLatencyMillis, clockRange(r), r.peakPssMb, r.gateWaitMinutes ?: 0.0, agreement(r))
                    )
            },
            leftFraction = 0.40f,
        )
        doc.figure(
            throughput(),
            "decodeTokensPerSecond per iteration. Same LiteRT build and delegate on all three; the Tab S7+ is fastest, " +
                "the Pixel decays the most, the Honor is flat.",
        )
        doc.figure(
            fastestCoreClock(),
            "max(cpuKhz) per iteration from IterationRecord.clocks. The Tab S7+ and the Honor read one value for the " +
                "whole run while the Pixel's big core moves, and the bundle records the clock, not the governor's reason.",
        )
        doc.figure(
            outputDifference(),
            "Per-iteration L-inf over the 64-element logit projection against the Pixel, in units of 1e-8. Tab S7+: " +
                "%.0f %% digest agreement, difference 0 on every iteration. Honor: %.0f %% digest agreement, max %.2fe-8. "
                    .format(digestAgreementWithPixel(samsung.bundle) * 100, digestAgreementWithPixel(honor.bundle) * 100, maxDeltaFromPixel(honor) * 1e8) +
                "The Pixel against its own 2026-11-12 run: %.0f %% agreement (not drawn; it also sits at 0)."
                    .format(digestAgreementWithPixel(pixelEarlier) * 100),
        )
        doc.p(
            "Tensor (Cortex-X1/A76/A55) and SM8250 (Cortex-A77/A55) are both Armv8.2 designs and agree bit for bit; " +
                "the SM8650 (Cortex-X4/A720/A520, Armv9.2) is the one that diverges. A plausible, untested explanation " +
                "is that XNNPACK picks different fp32 micro-kernels on the newer ISA, which you could test with a second " +
                "Armv9 device or with those kernels pinned on the same one.",
        )
        doc.table(
            "Repeat" to "Result",
            listOf(
                "Pixel 6: 2026-11-12 vs 2026-11-14" to
                    "%.0f %% of digests equal; median %.1f then %.1f inf/s."
                        .format(digestAgreement(pixelEarlier, pixel.bundle) * 100, median(pixelEarlier.observations.iterations.map { it.decodeTokensPerSecond }), pixel.median),
                "Galaxy Tab S7+: two runs %d min apart, the second after cooling".format(minutesBetweenTabletRuns) to
                    ("%.0f %% of digests equal; median %.1f then %.1f inf/s; first-50 to last-50 %s then %s; settled tail %s then %s; gate wait %.1f then %.1f min.")
                        .format(
                            digestAgreement(samsung.bundle, samsungRepeat.bundle) * 100, samsung.median, samsungRepeat.median,
                            pct(samsung.drift), pct(samsungRepeat.drift), steady(samsung), steady(samsungRepeat),
                            samsung.gateWaitMinutes ?: 0.0, samsungRepeat.gateWaitMinutes ?: 0.0,
                        ),
            ),
            leftFraction = 0.40f,
        )
        val (firstStart, firstEnd) = coreTempRange(samsung)
        val (repeatStart, repeatEnd) = coreTempRange(samsungRepeat)
        doc.figure(
            tabletCoreTemperature(),
            ("Max over the cpu-1-*-usr zones in ThermalSample.zoneTemperaturesCelsius, about 1 Hz, Tab S7+ only. First reading to " +
                "peak: %.1f to %.1f °C and %.1f to %.1f °C, most of the rise within the first second, with the fastest core fixed at " +
                "%d MHz, while throughput fell %s and %s first-50 to last-50. That is a slowdown without a clock change, " +
                "for which memory or cache behaviour as the die heats are untested candidates. " +
                "Both runs settle under the steady-state rule as revised on 2026-11-16; under the original rule the first did not, one of " +
                "the cases that showed it was brittle.")
                .format(firstStart, firstEnd, repeatStart, repeatEnd, samsung.fastestCoreMhz.first(), "%.1f %%".format(-samsung.drift), "%.1f %%".format(-samsungRepeat.drift)),
        )
    }

    /* ------------------------------------------------------------------ styling */

    internal fun base(xTitle: String, yTitle: String, xMax: Double = 300.0, xLabelEvery: Double = 50.0): XYChart {
        val chart = XYChartBuilder().width(WIDTH).height(HEIGHT).xAxisTitle(xTitle).yAxisTitle(yTitle).build()
        chart.styler.apply {
            chartBackgroundColor = Color.WHITE
            plotBackgroundColor = Color.WHITE
            isPlotBorderVisible = false
            isPlotGridVerticalLinesVisible = false
            plotGridLinesColor = GRID
            plotGridLinesStroke = BasicStroke(1.5f)
            axisTickLabelsColor = TEXT_SECONDARY
            axisTickMarksColor = GRID
            isAxisTicksLineVisible = false
            chartFontColor = TEXT_PRIMARY
            axisTitleFont = Font(Font.SANS_SERIF, Font.PLAIN, 26)
            axisTickLabelsFont = Font(Font.SANS_SERIF, Font.PLAIN, 24)
            legendFont = Font(Font.SANS_SERIF, Font.PLAIN, 24)
            // Below the plot, never over it: an inside legend hid the zero line in the difference chart.
            legendPosition = Styler.LegendPosition.OutsideS
            legendLayout = Styler.LegendLayout.Horizontal
            legendBorderColor = Color.WHITE
            legendBackgroundColor = Color.WHITE
            isChartTitleVisible = false
            chartPadding = 24
            xAxisMin = 0.0
            xAxisMax = xMax
            yAxisTickMarkSpacingHint = 90
            markerSize = 14
        }
        // xchart picks dense ticks that run together at print size; label only every xLabelEvery.
        chart.setCustomXAxisTickLabelsFormatter { x -> if (x % xLabelEvery == 0.0) "%.0f".format(x) else "" }
        return chart
    }

    internal fun line(
        chart: XYChart, name: String, x: List<Double>, y: List<Double>, colour: Color,
        dashed: Boolean = false, markers: Boolean = false,
    ): XYSeries = chart.addSeries(name, x, y).apply {
        marker = if (markers) (if (dashed) SeriesMarkers.DIAMOND else SeriesMarkers.CIRCLE) else SeriesMarkers.NONE
        markerColor = colour
        xySeriesRenderStyle = XYSeries.XYSeriesRenderStyle.Line
        lineColor = colour
        lineWidth = 3f
        if (dashed) lineStyle = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(12f, 10f), 0f)
    }

    internal fun render(chart: XYChart): BufferedImage = BitmapEncoder.getBufferedImage(chart)

    // Drawn at twice the printed size so lines stay crisp when scaled into the page.
    private const val WIDTH = 1600
    private const val HEIGHT = 720

    internal val PIXEL = Color(0x2a, 0x78, 0xd6)
    internal val HONOR = Color(0xeb, 0x68, 0x34)
    internal val SAMSUNG = Color(0x1b, 0xaf, 0x7a)
    private val GRID = Color(0xe4, 0xe3, 0xdf)
    private val TEXT_PRIMARY = Color(0x0b, 0x0b, 0x0b)
    private val TEXT_SECONDARY = Color(0x52, 0x51, 0x4e)
}
