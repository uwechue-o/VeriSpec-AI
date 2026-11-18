package ai.verispec.tools.report.docs

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Screenshots of the app's own charts. Unlike [CrossDeviceFigures], these are
 * not redrawn at build time: they are pictures of what the phone showed. The
 * Bundle Detail charts (resources/docs/app-screens-2026-11-14) were captured by
 * tools/report/scripts/capture_app_charts.py from the Pixel's cross-device run
 * (the same file CrossDeviceFigures draws from) and the Qwen starter-LLM smoke
 * test. The Live Run screen (resources/docs/app-screens-2026-11-16) was taken
 * with the phone's own screenshot button during the long MediaPipe run of
 * 2026-11-16 and cropped to the app's content.
 */
object AppScreenFigures {

    private const val DIR = "/docs/app-screens-2026-11-14/"
    private const val LIVE_DIR = "/docs/app-screens-2026-11-16/"

    private fun load(name: String, dir: String = DIR): BufferedImage =
        AppScreenFigures::class.java.getResourceAsStream(dir + name)?.use { ImageIO.read(it) }
            ?: error("missing screenshot resource $dir$name")

    val liveRun by lazy { load("pixel6-live-run.png", LIVE_DIR) }
    val efficientNetThroughput by lazy { load("pixel6-efficientnet-throughput.png") }
    val efficientNetTelemetry by lazy { load("pixel6-efficientnet-telemetry.png") }
    val qwenThroughput by lazy { load("pixel6-qwen-throughput.png") }

    /** What Bundle Detail's charts look like, as the plain-language editions tell it. */
    fun plainSection(doc: DocPdf) {
        doc.h2("What the app's charts look like")
        doc.p(
            "While a test is running, the Live Run screen shows how it is going. The picture below was taken on a " +
                "Pixel 6 on 16 November 2026, a third of the way through a long test of the built-in language model: " +
                "the model writing 128 tokens (pieces of words) at a time, 500 times over, with the phone unplugged " +
                "and its screen dimmed.",
        )
        doc.figure(
            liveRun,
            "The Live Run screen at repetition 133 of 500. For the first thirty or so repetitions the model wrote " +
                "about 24 tokens a second, the best rate so far, marked by the dashed peak line. Then the phone grew " +
                "warm enough to start slowing itself down (the green LIGHT label), the speed fell to about 15, and " +
                "it settled near 18, where it has stayed since. Thermal headroom is the phone's own measure of how " +
                "close it is to slowing down hard, from 0 (cool) to 1 (severe); here it reads 0.85. 120 of the 133 " +
                "repetitions ran while the phone was slowing itself down slightly; none has reached the stronger " +
                "level that would add red markers to the chart.",
            widthFraction = 0.55f,
        )
        doc.p(
            "Opening a finished result on the phone shows its charts. The pictures below are screenshots taken from a Pixel 6 " +
                "on 14 November 2026: first the image-recognition test that the cross-device comparison later in this " +
                "manual also uses, then the built-in language model's short trial.",
        )
        doc.figure(
            efficientNetThroughput,
            "Speed on each of 300 repetitions of the image-recognition test. The dashed lines mark the best speed the " +
                "phone sustained (peak) and the level it settled to (steady). The vertical axis starts at zero, so the " +
                "drop from one to the other is shown at its true size.",
        )
        doc.figure(
            efficientNetTelemetry,
            "The same test, shown against time: how fast the quickest processor core and the graphics processor were " +
                "running, the battery temperature, and how close the phone was to slowing itself down to shed heat " +
                "(0 is cool, 1 is severe). The phone stayed cool here. Temperature uses a narrow scale so that a " +
                "change of one degree would be visible. A chart only appears if the phone let the app read that signal.",
        )
        doc.figure(
            qwenThroughput,
            "Speed of the built-in language model, in tokens (pieces of words) per second, over its 10 repetitions. With " +
                "so few repetitions there is no settled level, so only the peak line is drawn.",
        )
        doc.p(
            "How these pictures were made: they are straight screenshots of the app, not charts redrawn for the " +
                "manual. The app draws its charts itself from the recorded numbers. The Live Run picture was taken " +
                "with the phone's own screenshot button while the test was running, and trimmed to the app's screen. " +
                "For the others, a small Python program " +
                "kept with the toolkit (tools/report/scripts/capture_app_charts.py) then scrolled the screen on the " +
                "connected phone, took screenshots through the standard Android developer tools, and cut each chart " +
                "out. No Python is involved in the other charts in this manual: those are drawn from the sealed result " +
                "files by the toolkit's own report software each time the manual is produced.",
        )
    }

    /** The same figures with the build details, for the developer editions. */
    fun developerSection(doc: DocPdf) {
        doc.h2("Live Run and Bundle Detail charts, as shown on device")
        doc.figure(
            liveRun,
            "Live Run during the long MediaPipe run of 2026-11-16 (manifest 5c8d8b52..., Qwen2.5-0.5B-Instruct int8, " +
                "CPU, 4 threads, maxNewTokens 128, ON_DIMMED, gate margin 0.40, unplugged), at iteration 133 of 500. " +
                "The chart is DecayChart over RunUiState.Running.decayChart: decode tok/s per iteration, y axis from 0, the " +
                "dashed line at the highest rate so far. About 24 tok/s for the first ~30 iterations, a drop to " +
                "about 15 as the device reached LIGHT, then a plateau near 18. Headroom 0.85; 120 of 133 iterations " +
                "started or ended at LIGHT or worse, none at MODERATE, so no red markers are drawn.",
            widthFraction = 0.55f,
        )
        doc.figure(
            efficientNetThroughput,
            "Throughput per iteration for the Pixel 6 cross-device bundle (2a5148676e8e..., EfficientNet-Lite0, 300 " +
                "iterations). Dashed lines are the envelope's windowed peak and settled-tail steady state; the y axis " +
                "starts at 0. Above 60 points only the line is drawn; throttled iterations would still get red markers.",
        )
        doc.figure(
            efficientNetTelemetry,
            "Telemetry over time for the same bundle: fastest core clock and GPU clock (per iteration, from " +
                "IterationRecord.clocks), battery temperature (powerTimeline) and thermal headroom (thermalTimeline), " +
                "all against seconds since run start. Battery temperature is the only series not drawn from 0.",
        )
        doc.figure(
            qwenThroughput,
            "The Qwen2.5-0.5B-Instruct starter-LLM smoke test (MediaPipe, 10 iterations): tok/s per iteration. No " +
                "steady state at this length, so only the peak reference line is drawn.",
        )
        doc.p(
            "How these are made: they are screenshots, not build-time renders. The Live Run figure was taken with " +
                "the device's own screenshot button mid-run and cropped to the app content (no status or navigation " +
                "bar); it lives in resources/docs/app-screens-2026-11-16. On device, the charts are drawn by " +
                "LineChart in app/ui/.../ui/common/Components.kt, a Compose Canvas chart with no charting library; " +
                "AxisScale picks round-number ticks (1, 2, 2.5 or 5 x 10^n) and TelemetrySeries.of extracts the " +
                "telemetry series from the bundle's Observations. tools/report/scripts/capture_app_charts.py (Python 3, " +
                "Pillow) then drives the open Bundle Detail screen over adb: it scrolls with input swipe, reads label " +
                "bounds from uiautomator dump, crops each chart out of an adb exec-out screencap image, and tiles the " +
                "four telemetry charts two by two. The PNGs are committed under " +
                "tools/report/src/main/resources/docs/app-screens-2026-11-14 and AppScreenFigures embeds them with " +
                "DocPdf.figure. Unlike the cross-device charts (Kotlin and XChart, redrawn from the bundles on every " +
                "generateDocs run, no Python), these do not change when the chart code changes: after changing " +
                "LineChart, reinstall the app, re-run the script on both bundles and regenerate the manuals.",
        )
    }
}
