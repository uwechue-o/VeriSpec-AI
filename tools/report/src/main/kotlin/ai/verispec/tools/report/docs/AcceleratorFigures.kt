package ai.verispec.tools.report.docs

import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.model.acceleratorSummary
import ai.verispec.core.evidence.BundleCodec
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * Charts and section text for the first accelerator runs (2026-11-18): the Galaxy Tab S7+
 * and the OnePlus 8T, both Snapdragon 865 (SM8250), each running EfficientNet-Lite0 on
 * LiteRT through NNAPI and on the CPU, and the Qwen starter on MediaPipe's CPU path, with
 * the Pixel 6's MediaPipe run for reference. Drawn from the signed bundles kept under
 * resources/docs/accelerators-2026-11-18. The MediaPipe GPU attempts signed nothing (they
 * stop at warm-up 1), so they appear in the tables only.
 *
 * Colours: the Tab S7+ and the Pixel keep their cross-device colours; the OnePlus is plum.
 * NNAPI is drawn solid and the CPU dashed.
 */
object AcceleratorFigures {

    private const val DIR = "/docs/accelerators-2026-11-18/"

    private fun load(name: String, runtime: RuntimeId, delegate: Delegate): EvidenceBundle {
        val text = AcceleratorFigures::class.java.getResource(DIR + name)?.readText() ?: error("missing bundle resource $DIR$name")
        return BundleCodec.decode(text).also {
            check(it.manifest.backend.runtime == runtime && it.manifest.backend.delegate == delegate) {
                "$name is not a $runtime $delegate run"
            }
        }
    }

    val tabNnapi by lazy { load("tab-s7plus-litert-nnapi.json", RuntimeId.LITERT, Delegate.NNAPI) }
    val tabCpu by lazy { load("tab-s7plus-litert-cpu.json", RuntimeId.LITERT, Delegate.CPU) }
    val oneplusNnapi by lazy { load("oneplus8t-litert-nnapi.json", RuntimeId.LITERT, Delegate.NNAPI) }
    val oneplusCpu by lazy { load("oneplus8t-litert-cpu.json", RuntimeId.LITERT, Delegate.CPU) }
    val tabLlm by lazy { load("tab-s7plus-mediapipe-cpu.json", RuntimeId.MEDIAPIPE, Delegate.CPU) }
    val oneplusLlm by lazy { load("oneplus8t-mediapipe-cpu.json", RuntimeId.MEDIAPIPE, Delegate.CPU) }
    val pixelLlm by lazy { load("pixel6-mediapipe-cpu.json", RuntimeId.MEDIAPIPE, Delegate.CPU) }

    /* Schema 1.3.0: the same OnePlus run with NNAPI bound to one named device. */
    val namedGpu by lazy { load("oneplus8t-litert-nnapi-qti-gpu.json", RuntimeId.LITERT, Delegate.NNAPI) }
    val namedDefault by lazy { load("oneplus8t-litert-nnapi-qti-default.json", RuntimeId.LITERT, Delegate.NNAPI) }
    val namedHta by lazy { load("oneplus8t-litert-nnapi-qti-hta.json", RuntimeId.LITERT, Delegate.NNAPI) }

    /** Comparisons are per iteration index, so the runs being compared must share model and seed. */
    private fun checkComparable(bundles: List<EvidenceBundle>) {
        val keys = bundles.map { it.manifest.model.sha256 to it.manifest.seed }.toSet()
        check(keys.size == 1) { "accelerator figures compare runs of one model and seed, found $keys" }
    }

    /* ------------------------------------------------------------------ charts */

    /** Inferences per second per iteration: NNAPI solid, CPU dashed, one colour per device. */
    fun liteThroughput(): BufferedImage {
        checkComparable(listOf(tabNnapi, tabCpu, oneplusNnapi, oneplusCpu))
        val chart = CrossDeviceFigures.base("Iteration", "Inferences per second", xMax = 30.0, xLabelEvery = 5.0)
        chart.styler.yAxisMin = 0.0
        chart.styler.yAxisMax = 100.0
        listOf(
            Triple(tabNnapi, "Galaxy Tab S7+, NNAPI", CrossDeviceFigures.SAMSUNG to false),
            Triple(tabCpu, "Galaxy Tab S7+, CPU", CrossDeviceFigures.SAMSUNG to true),
            Triple(oneplusNnapi, "OnePlus 8T, NNAPI", ONEPLUS to false),
            Triple(oneplusCpu, "OnePlus 8T, CPU", ONEPLUS to true),
        ).forEach { (b, name, style) ->
            CrossDeviceFigures.line(chart, name, indices(b), throughput(b), style.first, dashed = style.second)
        }
        return CrossDeviceFigures.render(chart)
    }

    /**
     * Largest difference between the NNAPI and the CPU logit projection, per iteration, in
     * units of 1e-5. The OnePlus is dashed because it lies exactly on the tablet's line.
     */
    fun nnapiDifference(): BufferedImage {
        checkComparable(listOf(tabNnapi, tabCpu, oneplusNnapi, oneplusCpu))
        val chart = CrossDeviceFigures.base("Iteration", "NNAPI vs CPU, largest difference (× 10⁻⁵)", xMax = 30.0, xLabelEvery = 5.0)
        chart.styler.yAxisMin = 0.0
        listOf(Triple(tabNnapi, tabCpu, "Galaxy Tab S7+"), Triple(oneplusNnapi, oneplusCpu, "OnePlus 8T")).forEach { (nn, cpu, name) ->
            val delta = maxDelta(cpu, nn)
            CrossDeviceFigures.line(
                chart, name, delta.keys.map { it.toDouble() }, delta.values.map { it * 1e5 },
                if (nn === tabNnapi) CrossDeviceFigures.SAMSUNG else ONEPLUS, dashed = nn === oneplusNnapi, markers = true,
            )
        }
        return CrossDeviceFigures.render(chart)
    }

    /** MediaPipe decode tokens per second per iteration on the CPU of all three devices. */
    fun llmThroughput(): BufferedImage {
        checkComparable(listOf(tabLlm, oneplusLlm, pixelLlm))
        val chart = CrossDeviceFigures.base("Iteration", "Tokens per second", xMax = 30.0, xLabelEvery = 5.0)
        chart.styler.yAxisMin = 0.0
        chart.styler.yAxisMax = 30.0
        listOf(
            Triple(pixelLlm, "Pixel 6", CrossDeviceFigures.PIXEL),
            Triple(tabLlm, "Galaxy Tab S7+", CrossDeviceFigures.SAMSUNG),
            Triple(oneplusLlm, "OnePlus 8T", ONEPLUS),
        ).forEach { (b, name, colour) -> CrossDeviceFigures.line(chart, name, indices(b), throughput(b), colour, markers = true) }
        return CrossDeviceFigures.render(chart)
    }

    /**
     * The OnePlus with NNAPI bound to each of three devices, inferences per second per
     * iteration. The run that qti-hta refused is labelled by what it actually ran on.
     */
    fun namedDeviceThroughput(): BufferedImage {
        checkComparable(listOf(namedGpu, namedDefault, namedHta))
        val chart = CrossDeviceFigures.base("Iteration", "Inferences per second", xMax = 30.0, xLabelEvery = 5.0)
        chart.styler.yAxisMin = 0.0
        chart.styler.yAxisMax = 100.0
        listOf(
            Triple(namedGpu, CrossDeviceFigures.PIXEL, false),
            Triple(namedDefault, CrossDeviceFigures.HONOR, true),
            Triple(namedHta, ONEPLUS, false),
        ).forEach { (b, colour, dashed) ->
            val p = b.provenance
            val ran = if (p.acceleratorActuallyUsed == p.accelerator) "ran there" else "refused, ran on the ${p.delegateActuallyUsed}"
            CrossDeviceFigures.line(chart, "${p.accelerator}: $ran", indices(b), throughput(b), colour, dashed = dashed)
        }
        return CrossDeviceFigures.render(chart)
    }

    /* ------------------------------------------------------------------ numbers */

    private fun indices(b: EvidenceBundle) = b.observations.iterations.map { it.index.toDouble() }
    private fun throughput(b: EvidenceBundle) = b.observations.iterations.map { it.decodeTokensPerSecond }
    /** The true median (mean of the middle two for an even count), as quoted in CLAUDE.md and the run reports. */
    private fun median(b: EvidenceBundle): Double =
        throughput(b).sorted().let { if (it.size % 2 == 0) (it[it.size / 2 - 1] + it[it.size / 2]) / 2 else it[it.size / 2] }
    private fun min(b: EvidenceBundle): Double = throughput(b).min()
    private fun max(b: EvidenceBundle): Double = throughput(b).max()
    private fun throttled(b: EvidenceBundle): Int = b.observations.iterations.count { it.wasThrottled }
    private fun tokens(b: EvidenceBundle): Int = b.observations.iterations.first().decodeTokens
    private fun charging(b: EvidenceBundle): Boolean = b.observations.powerTimeline.any { it.isCharging }

    /** Share of iterations whose output digest equals the other run's at the same index. */
    fun digestAgreement(a: EvidenceBundle, b: EvidenceBundle): Double {
        val digests = a.observations.outputDigests.associate { it.iterationIndex to it.sha256 }
        return b.observations.outputDigests.count { digests[it.iterationIndex] == it.sha256 }.toDouble() / b.observations.outputDigests.size
    }

    private fun maxDelta(a: EvidenceBundle, b: EvidenceBundle): Map<Int, Double> {
        val other = b.observations.outputDigests.associateBy { it.iterationIndex }
        return a.observations.outputDigests.mapNotNull { d ->
            val x = d.logitProjection ?: return@mapNotNull null
            val y = other[d.iterationIndex]?.logitProjection ?: return@mapNotNull null
            d.iterationIndex to x.indices.maxOf { abs(x[it].toDouble() - y[it].toDouble()) }
        }.toMap().toSortedMap()
    }

    private fun maxDifference(a: EvidenceBundle, b: EvidenceBundle): Double = maxDelta(a, b).values.max()

    private fun batteryRange(b: EvidenceBundle): String =
        b.observations.powerTimeline.mapNotNull { it.batteryTemperatureDeciC?.div(10.0) }
            .let { "%.1f to %.1f °C".format(it.first(), it.max()) }

    private fun pct(x: Double) = "%.0f %%".format(x * 100)

    /* ------------------------------------------------------------------ sections */

    /** The accelerator runs as the plain-language manual tells them. */
    fun plainSection(doc: DocPdf) {
        doc.h2("The first tries on graphics and AI chips")
        doc.p(
            "On 18 November 2026 two devices built on the same Qualcomm chip, the Samsung Galaxy Tab S7+ tablet and a " +
                "OnePlus 8T phone, were asked to run the tests somewhere other than the main processor. Both are old " +
                "enough to still offer Android's general route to AI accelerators, which newer Android versions have " +
                "dropped. Each test ran 30 times and every sealed result checked out. All of these runs, and the Pixel 6 " +
                "run they are compared with, were made with a cable connected, so the batteries were charging. The " +
                "OnePlus was also slowing itself down to manage heat from the very first repetition, so its speeds are " +
                "lower and less even than a cool, unplugged phone would give.",
        )
        doc.table(
            "Test" to "What happened",
            listOf(
                "The language model on the graphics processor (both devices)" to
                    "Failed on both, exactly as on the Pixel 6: the graphics processor did the work but no text came " +
                    "out. The app stopped cleanly at the first warm-up and sealed nothing, so there is no speed to show.",
                "The image model on Android's accelerator route" to
                    "Tablet %.1f, OnePlus %.1f inferences a second, against %.1f and %.1f on their main processors. "
                        .format(median(tabNnapi), median(oneplusNnapi), median(tabCpu), median(oneplusCpu)) +
                    "Android's own records show both handed the work to a general-purpose path, most likely the graphics " +
                    "processor, not to the dedicated AI chip.",
                "The image model on the main processor" to
                    "Both gave answers identical to the Pixel 6's on every repetition, as in the earlier comparison.",
                "The language model on the main processor" to
                    "Tablet %.1f, OnePlus %.1f, Pixel 6 %.1f tokens a second, and the same answer on all three every time."
                        .format(median(tabLlm), median(oneplusLlm), median(pixelLlm)),
            ),
            leftFraction = 0.40f,
        )
        doc.figure(
            liteThroughput(),
            "The image model's speed on each repetition. Solid lines used Android's accelerator route, dashed lines the " +
                "main processor. The accelerator route was faster on both devices; the OnePlus's lines are lower and " +
                "jumpier because it was already hot.",
        )
        doc.figure(
            nnapiDifference(),
            "How far the accelerator route's answers were from the main processor's on the same device. The gaps are " +
                "thousands of times larger than the gaps between different phones' main processors, which is what " +
                "a chip working in lower precision would produce. The two devices' lines lie exactly on top of each " +
                "other: on the accelerator route, the tablet and the OnePlus gave identical answers.",
        )
        doc.figure(
            llmThroughput(),
            "The language model on each device's main processor. The OnePlus started slowly while hot, " +
                "then caught up with the others.",
        )
        doc.p(
            "So the accelerator route works and is faster, but on these devices it doesn't reach the AI chip. At the " +
                "time, a sealed result could only say that the route was used, not which chip did the work.",
        )
        checkComparable(listOf(namedGpu, namedDefault, namedHta, oneplusCpu))
        doc.p(
            "That has since changed. A test can now name the one chip it wants, and the sealed result lists every " +
                "chip the device offered, records which one the test was tied to, and says how much of the model " +
                "that chip actually took. Later on 18 November the OnePlus ran the image model three more times, each " +
                "tied to a different chip. The graphics chip took the whole model and ran at %.1f inferences a second. "
                    .format(median(namedGpu)) +
                "Android's default choice gave exactly the same answers as the graphics chip, which settles what it " +
                "was. The dedicated AI chip refused the whole model, because it only works with smaller number " +
                "formats, and the sealed result says so: it records that the test fell back to the main processor, " +
                "and its answers match the main processor's exactly. So nothing has run on an AI chip yet, but the " +
                "toolkit can now ask for one and will say plainly whether it got it. Reaching the AI chip will most " +
                "likely need a model stored in a smaller number format.",
        )
    }

    /** The accelerator runs as the developer manual tells them. */
    fun developerSection(doc: DocPdf) {
        check(listOf(tabNnapi, tabCpu, oneplusNnapi, oneplusCpu, tabLlm, oneplusLlm, pixelLlm).all(::charging)) {
            "the text below says every accelerator-run bundle was charging"
        }
        doc.h2("The first accelerator runs")
        doc.p(
            "2026-11-18, Galaxy Tab S7+ (SM-T970, Android 13) and OnePlus 8T (Android 14), both SM8250, so LiteRT offers " +
                "NNAPI. 30 iterations, seed 42, 4 threads, no gate, every bundle verifying TRUSTWORTHY. The charts are " +
                "rendered from the bundles in tools/report/src/main/resources/docs/accelerators-2026-11-18, together with " +
                "the Pixel 6's MediaPipe CPU run for reference. The MediaPipe GPU attempts signed nothing, so they appear " +
                "in the table only. Every bundle here, the Pixel's included, was driven over USB and records charging. " +
                ("The OnePlus was also at LIGHT or worse on every iteration (%d/30, %d/30 and %d/30; the Tab's LiteRT " +
                    "runs %d/30 and %d/30), so its figures are not a cool-device measurement.")
                    .format(throttled(oneplusNnapi), throttled(oneplusCpu), throttled(oneplusLlm), throttled(tabNnapi), throttled(tabCpu)),
        )
        doc.table(
            "Run" to "Measured",
            listOf(
                "MediaPipe Qwen int8, GPU (both)" to
                    "1,303 of 1,360 ops on ML_DRIFT_CL (OpenCL, Adreno), then DetokenizerCalculator id -1; stops at " +
                    "warm-up 1 with RunFailure.InferenceFailed and no bundle, as on the Pixel's Mali.",
                "LiteRT EfficientNet-Lite0, NNAPI" to
                    ("Tab S7+ median %.1f inf/s (%.1f-%.1f); OnePlus %.1f (%.1f-%.1f). All 62 ops on qti-default " +
                        "(qti-dsp, qti-gpu, qti-hta also listed). %s of OnePlus digests equal the Tab's.")
                        .format(
                            median(tabNnapi), min(tabNnapi), max(tabNnapi), median(oneplusNnapi), min(oneplusNnapi), max(oneplusNnapi),
                            pct(digestAgreement(tabNnapi, oneplusNnapi)),
                        ),
                "LiteRT EfficientNet-Lite0, CPU" to
                    "Tab S7+ median %.1f inf/s; OnePlus %.1f (%.1f-%.1f). %s of OnePlus digests equal the Tab's (which match the Pixel's)."
                        .format(median(tabCpu), median(oneplusCpu), min(oneplusCpu), max(oneplusCpu), pct(digestAgreement(tabCpu, oneplusCpu))),
                "NNAPI vs CPU, same device" to
                    "%s and %s digests equal; max projection L∞ %.2e (Tab) and %.2e (OnePlus), against ~1e-8 between SoCs on the CPU."
                        .format(
                            pct(digestAgreement(tabCpu, tabNnapi)), pct(digestAgreement(oneplusCpu, oneplusNnapi)),
                            maxDifference(tabCpu, tabNnapi), maxDifference(oneplusCpu, oneplusNnapi),
                        ),
                "MediaPipe Qwen int8, CPU" to
                    ("Pixel 6 %.1f tok/s, Tab S7+ %.1f, OnePlus %.1f (%.1f-%.1f, battery %s); %d tokens each; " +
                        "digest agreement with the Pixel %s (Tab) and %s (OnePlus).")
                        .format(
                            median(pixelLlm), median(tabLlm), median(oneplusLlm), min(oneplusLlm), max(oneplusLlm), batteryRange(oneplusLlm),
                            tokens(oneplusLlm), pct(digestAgreement(pixelLlm, tabLlm)), pct(digestAgreement(pixelLlm, oneplusLlm)),
                        ),
            ),
            leftFraction = 0.30f,
        )
        doc.figure(
            liteThroughput(),
            "decodeTokensPerSecond per iteration (inf/s for a tensor model); NNAPI solid, CPU dashed. The OnePlus NNAPI " +
                "run is the noisiest line, with a coefficient of variation near 30 %, at LIGHT throughout.",
        )
        doc.figure(
            nnapiDifference(),
            "Per-iteration L∞ over the 64-element logit projection, NNAPI against CPU on the same device, × 1e-5. Both " +
                "devices give the same curve because both NNAPI runs and both CPU runs agree bit for bit across devices: " +
                "the driver path, not the device, sets the difference. Magnitude consistent with fp16 relaxed precision on " +
                "the Adreno; the tablet's GPU clock rose to 587-670 MHz from 305 MHz on the CPU run. The OnePlus exposes " +
                "no GPU clock node, so on that device the GPU attribution rests on the identical outputs alone.",
        )
        doc.figure(
            llmThroughput(),
            "MediaPipe decode tok/s per iteration on the CPU, 36 tokens per iteration. The OnePlus run dips to %.1f in the first iterations, at LIGHT, then recovers."
                .format(min(oneplusLlm)),
        )
        namedSection(doc)
    }

    /** Schema 1.3.0: NNAPI bound to a named device, and what the bundles record. */
    private fun namedSection(doc: DocPdf) {
        checkComparable(listOf(namedGpu, namedDefault, namedHta, oneplusCpu, oneplusNnapi))
        doc.h2("Naming the NNAPI device (schema 1.3.0)")
        doc.p(
            "The runs above could not say which NNAPI device ran: the bundle recorded NNAPI and nothing more. " +
                "Schema 1.3.0 adds BackendRef.accelerator, which LiteRtBackend passes to " +
                "NnApiDelegate.Options.setAcceleratorName, binding the delegate to that one device. The provenance " +
                "records it as accelerator and acceleratorActuallyUsed, plus acceleratorsAvailable: every device " +
                "ANeuralNetworks_getDevice lists, with its NDK type, driver version and feature level, read through a " +
                "small JNI library (libverispec_nnapi) because LiteRT's Java API cannot list them. All three are " +
                "optional and absent when unused, so older bundles and manifest ids are unchanged. The Run Composer " +
                "offers the listed devices; Bundle Detail, verispec inspect and the PDF report print one shared " +
                "summary (ProvenanceRecord.acceleratorSummary), which never credits a device to an unnamed run.",
        )
        doc.p(
            "Binding is not proof of execution. A named device that supports no operation is still accepted by " +
                "the delegate, and LiteRT then runs the whole graph on XNNPACK without an error: the first qti-hta run " +
                "on the OnePlus was signed as NNAPI on qti-hta although its digests equal the CPU run's on every " +
                "iteration. LiteRT's Java API cannot count delegated operations, so DelegationLog writes a marker to " +
                "the app's own log before the interpreter is built and reads back LiteRT's \"Replacing N out of M " +
                "node(s) with delegate (TfLiteNnapiDelegate)\" line. None taken: the run is recorded as CPU and the " +
                "device as not used, with a note. Some or all taken: the count goes into the provenance notes. Log " +
                "unreadable: a note says the share is not recorded. The check applies to unnamed NNAPI runs too.",
        )
        doc.table(
            "OnePlus 8T, Lite0, NNAPI bound to" to "Recorded",
            listOf(namedGpu, namedDefault, namedHta).map { b ->
                val p = b.provenance
                val type = p.acceleratorsAvailable?.firstOrNull { it.name == p.accelerator }?.type?.name?.lowercase() ?: "?"
                "${p.accelerator} ($type)" to
                    "delegate %s → %s; %s; median %.1f inf/s; digests equal to the unnamed NNAPI run %s, to the CPU run %s. %s"
                        .format(
                            p.delegate, p.delegateActuallyUsed, p.acceleratorSummary()?.substringBefore(";") ?: "-", median(b),
                            pct(digestAgreement(oneplusNnapi, b)), pct(digestAgreement(oneplusCpu, b)),
                            p.notes.firstOrNull { "LiteRT log" in it }
                                ?: "Signed before the delegation-log check; logcat showed 62 of 62 operations on NNAPI.",
                        )
            },
            leftFraction = 0.30f,
        )
        doc.p(
            ("The OnePlus lists five devices: %s. qti-default reports itself as an ACCELERATOR, yet its outputs equal " +
                "qti-gpu's on every iteration, as did the unnamed runs, so on this phone NNAPI's default is the Adreno GPU. " +
                "The qti-hta driver logged \"{CONVOLUTION_2D, TENSOR_FP32} is not supported\" for every convolution. The " +
                "named runs had %d, %d and %d of 30 iterations throttled against 30 of 30 for the unnamed ones, which is " +
                "why they are faster. A real NPU result still needs an int8 starter.")
                .format(
                    namedGpu.provenance.acceleratorsAvailable.orEmpty().joinToString(", ") { "${it.name} (${it.type.name})" },
                    throttled(namedGpu), throttled(namedDefault), throttled(namedHta),
                ),
        )
    }

    private val ONEPLUS = Color(0xb0, 0x3a, 0x8c)
}
