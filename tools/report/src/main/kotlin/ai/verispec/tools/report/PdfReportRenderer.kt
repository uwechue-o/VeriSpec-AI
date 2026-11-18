package ai.verispec.tools.report

import ai.verispec.core.domain.math.SustainedLoad
import ai.verispec.core.domain.model.CoverageStatus
import ai.verispec.core.domain.model.EnvelopeValidity
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.math.BatteryCurrent
import ai.verispec.core.domain.model.HardwareSummary
import ai.verispec.core.domain.model.VerificationReport
import ai.verispec.core.domain.model.Workload
import ai.verispec.tools.analysis.DecayAnalysis
import ai.verispec.tools.analysis.DivergenceReport
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Human-readable PDF that goes straight into a submission binder.
 *
 * PDFBox (Apache-2.0) on purpose: iText 7 is AGPL, which would force AGPL on
 * downstream medical-device and automotive adopters or push them to a
 * commercial licence. That is disqualifying for this toolkit.
 */
class PdfReportRenderer {

    data class Input(
        val bundle: EvidenceBundle,
        val decay: DecayAnalysis?,
        val verification: VerificationReport?,
        val divergence: DivergenceReport?,
    )

    fun render(input: Input): ByteArray {
        PDDocument().use { doc ->
            val page = Page(doc)
            header(page, input.bundle)
            summary(page, input)
            provenance(page, input.bundle)
            device(page, input.bundle)
            performance(page, input)
            divergence(page, input.divergence)
            clauses(page, input.bundle)
            page.close()

            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    /* ------------------------------------------------------------------ sections */

    private fun header(p: Page, b: EvidenceBundle) {
        p.title("VeriSpec-AI conformance evidence")
        p.small("Schema ${b.schemaVersion} · tool ${b.provenance.toolVersion} · generated ${nowIso()}")
        p.gap(4f)
        p.h2(b.manifest.label.ifBlank { "Unlabelled run" })
        p.mono("bundle   ${b.bundleId.value}")
        p.mono("manifest ${b.manifest.manifestId.value}")
        p.gap(6f)
    }

    private fun summary(p: Page, input: Input) {
        val b = input.bundle
        p.h2("Verification")
        val v = input.verification
        if (v == null) {
            p.badge("NOT VERIFIED IN THIS REPORT", Color(0.4f, 0.4f, 0.4f))
            p.body("This report was rendered without running verification. Run `verispec verify` to attach a result.")
        } else {
            p.badge(
                if (v.isTrustworthy) "SIGNATURE AND CHAIN VERIFIED" else "VERIFICATION FAILED",
                if (v.isTrustworthy) OK else BAD,
            )
            p.kv("Canonical content hash", if (v.canonicalHashMatches) "matches bundle id" else "MISMATCH — content altered after signing")
            p.kv("Detached signature", if (v.signatureValid) "valid against leaf certificate" else "INVALID")
            p.kv("Attestation chain", if (v.attestationChainValid) "valid" else "INVALID")
            p.kv("Hardware-backed key", v.hardwareBacked.toString())
            p.kv("StrongBox", v.strongBox.toString())
            p.kv("Challenge bound to manifest", v.challengeBoundToManifest.toString())
            v.issues.take(MAX_LIST).forEach { p.bullet(it) }
        }
        p.gap(4f)
    }

    private fun provenance(p: Page, b: EvidenceBundle) {
        p.h2("Provenance")
        p.kv("Model SHA-256", b.provenance.modelSha256.hex)
        p.kv("Model format / quantization", "${b.provenance.modelFormat} · ${b.manifest.model.quantization.scheme}")
        p.kv("Parameters", b.manifest.model.parameterCount?.toString() ?: "not declared")
        p.kv("Runtime", "${b.provenance.runtime} declared ${b.provenance.runtimeVersionDeclared}, observed ${b.provenance.runtimeVersionObserved ?: "not reported"}")
        p.kv("Delegate", "requested ${b.provenance.delegate}, executed on ${b.provenance.delegateActuallyUsed}")
        p.kv("NPU driver", b.provenance.npuDriverVersion ?: "not reported by platform")
        p.kv("GPU driver", b.provenance.gpuDriverVersion ?: "not reported by platform")
        b.provenance.notes.take(MAX_LIST).forEach { p.bullet(it) }
        p.gap(4f)
    }

    private fun device(p: Page, b: EvidenceBundle) {
        p.h2("Device")
        p.kv("Manufacturer / model", "${b.device.manufacturer} ${b.device.deviceModel}")
        p.kv("SoC", "${b.device.soc.vendor} ${b.device.soc.model}${b.device.soc.revision?.let { " rev $it" } ?: ""}")
        p.kv("Android", "${b.device.androidRelease} (API ${b.device.apiLevel}), security patch ${b.device.securityPatch}")
        p.kv("Build fingerprint", b.device.fingerprint)
        p.kv("RAM / cores", "%.1f GB · %d".format(b.device.totalRamBytes / 1_073_741_824.0, b.device.cpuCoreCount))
        p.kv("Thermal HAL", b.device.thermalHalVersion ?: "not reported")
        p.gap(4f)
    }

    private fun performance(p: Page, input: Input) {
        val b = input.bundle
        p.h2("Sustained-load performance envelope")
        val d = input.decay
        if (d == null) {
            p.body("No iterations recorded.")
            return
        }
        p.kv("Iterations", "${d.iterations} (warmup ${b.manifest.schedule.warmupIterations} excluded)")
        // Tensor runs count one forward pass per decode unit, so their throughput is inferences per second.
        val unit = if (b.manifest.workload is Workload.TensorInference) "inf/s" else "tok/s"
        p.kv("Validity", validityText(d.validity))
        b.observations.preconditions?.let { gate ->
            p.kv("Start conditions", if (gate.satisfied) "gate satisfied after %.0f s".format(gate.waitedMillis / 1000.0) else "gate NOT satisfied")
            gate.unmetAtStart.forEach { p.kv("  unmet at start", it) }
        }
        p.kv("Peak decode throughput", "%.2f $unit (best %d-iteration median)".format(d.peakTokensPerSecond, SustainedLoad.PEAK_WINDOW))
        p.kv(
            "Steady-state throughput",
            d.steadyStateTokensPerSecond?.let { "%.2f $unit (settled from iteration %d)".format(it, d.steadyStateFromIteration) }
                ?: "not settled: the run was still changing when it ended",
        )
        p.kv("Decay", d.decayPercent?.let { "%.1f %% from peak".format(it) } ?: "not measurable without a steady state")
        p.kv("Regression slope", "%.4f $unit per iteration (R² %.3f)".format(d.slopePerIteration, d.rSquared))
        p.kv("Time to first throttle", d.timeToFirstThrottleMillis?.let { "%.1f s".format(it / 1000.0) } ?: "never throttled")
        p.kv("Throttled iterations", "%.0f %% at LIGHT or worse, %.0f %% at MODERATE or worse".format(d.throttledFraction * 100, d.materiallyThrottledFraction * 100))
        p.kv("Max thermal status", d.maxThermalStatus.name)
        p.kv("Min thermal headroom", d.minHeadroom?.let { "%.2f".format(it) } ?: "not reported")
        p.kv("Time to first token p95 / p99", "%.0f / %.0f µs".format(d.p95TimeToFirstTokenMicros, d.p99TimeToFirstTokenMicros))
        p.kv("Throughput variability", "%.1f %% CoV".format(d.coefficientOfVariation * 100))
        d.meanPowerMilliWatts?.let { p.kv("Mean power", "%.0f mW while discharging".format(it)) }
        if (d.batteryCurrentUnit == BatteryCurrent.Unit.MILLIAMPS) {
            p.kv("Battery current", "device reports mA, not the documented µA; scaled by 1,000")
        }
        d.energyPerTokenMilliJoules?.let { p.kv("Energy per token", "%.2f mJ".format(it)) }
        p.kv("Peak PSS", "%.1f MB".format(b.observations.memoryPeaks.peakPssKb / 1024.0))
        HardwareSummary.of(b.observations)?.rows()?.forEach { (k, v) -> p.kv(k.replaceFirstChar { it.uppercase() }, v) }

        val chart = ChartRenderer.decayChart(b)
        if (chart != null) p.image(LosslessFactory.createFromImage(p.doc, chart), CHART_WIDTH, CHART_HEIGHT)
        p.gap(4f)
    }

    private fun divergence(p: Page, report: DivergenceReport?) {
        p.h2("Cross-device numerical divergence")
        if (report == null) {
            p.body("Not computed: this report covers a single bundle. Run `verispec diverge` over bundles from two or more devices.")
            p.gap(4f)
            return
        }
        p.badge(
            if (report.bitIdenticalAcrossFleet) "OUTPUTS IDENTICAL ACROSS FLEET" else "OUTPUTS DIVERGE ACROSS FLEET",
            if (report.bitIdenticalAcrossFleet) OK else WARN,
        )
        p.kv("Reference device", "${report.reference.socModel} · ${report.reference.delegate} · ${report.reference.bundleId.take(12)}…")
        report.pairwise.forEach { d ->
            p.body("${d.candidate.socModel} / ${d.candidate.delegate}")
            p.kv("  Digest agreement", "%.2f %% over %d iterations".format(d.digestAgreementRate * 100, d.comparedIterations))
            d.firstDivergentIteration?.let { p.kv("  First divergent iteration", it.toString()) }
            if (d.projectionAvailable) {
                p.kv("  Cosine similarity", "mean %.6f, min %.6f".format(d.meanCosineSimilarity, d.minCosineSimilarity))
                p.kv("  Max L∞ delta", "%.6f".format(d.maxLInfDelta))
                p.kv("  Mean absolute delta", "%.6f".format(d.meanAbsoluteDelta))
                p.kv("  Max symmetric KL", "%.6f".format(d.maxSymmetricKl))
            } else {
                p.kv("  Projection metrics", "unavailable (logit projections not recorded)")
            }
        }
        report.warnings.take(MAX_LIST).forEach { p.bullet(it) }
        p.gap(4f)
    }

    private fun clauses(p: Page, b: EvidenceBundle) {
        p.h2("Regulatory clause coverage")
        if (b.clauseCoverage.isEmpty()) {
            p.body("No clause catalog was applied to this bundle.")
            return
        }
        val gaps = b.clauseCoverage.filter { it.status != CoverageStatus.SATISFIED }
        p.badge(
            if (gaps.isEmpty()) "ALL CATALOGUED CLAUSES EVIDENCED" else "${gaps.size} CLAUSE(S) NOT FULLY EVIDENCED",
            if (gaps.isEmpty()) OK else BAD,
        )
        b.clauseCoverage.groupBy { it.framework }.toSortedMap(compareBy { it.ordinal }).forEach { (framework, list) ->
            p.h3(framework.displayName)
            // Gaps first: a compliance report that leads with green is a liability.
            list.sortedBy { it.status.ordinal * -1 }.forEach { c ->
                val colour = when (c.status) {
                    CoverageStatus.SATISFIED -> OK
                    CoverageStatus.PARTIAL -> WARN
                    CoverageStatus.NOT_COVERED -> BAD
                }
                p.clause(c.status.name.replace('_', ' '), colour, "${c.reference} — ${c.title}")
                c.gapDescription?.let { p.small("      $it") }
            }
        }
    }

    /* ------------------------------------------------------------------ layout */

    private class Page(val doc: PDDocument) {
        private var page = PDPage(PDRectangle.A4)
        private var stream: PDPageContentStream
        private var y = 0f

        init {
            doc.addPage(page)
            stream = PDPageContentStream(doc, page)
            y = PDRectangle.A4.height - MARGIN
        }

        fun close() = stream.close()

        private fun ensure(space: Float) {
            if (y - space > MARGIN) return
            stream.close()
            page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            stream = PDPageContentStream(doc, page)
            y = PDRectangle.A4.height - MARGIN
        }

        private fun text(s: String, font: PDType1Font, size: Float, colour: Color, indent: Float = 0f) {
            val safe = sanitize(s)
            val maxChars = ((PDRectangle.A4.width - 2 * MARGIN - indent) / (size * 0.5f)).toInt().coerceAtLeast(20)
            safe.chunked(maxChars).forEach { line ->
                ensure(size + 3f)
                stream.beginText()
                stream.setFont(font, size)
                stream.setNonStrokingColor(colour)
                stream.newLineAtOffset(MARGIN + indent, y)
                stream.showText(line)
                stream.endText()
                y -= size + 3f
            }
        }

        fun title(s: String) = text(s, BOLD, 18f, INK)
        fun h2(s: String) { gap(6f); text(s, BOLD, 13f, INK) }
        fun h3(s: String) { gap(3f); text(s, BOLD, 11f, INK) }
        fun body(s: String) = text(s, REGULAR, 10f, INK)
        fun small(s: String) = text(s, REGULAR, 8.5f, MUTED)
        fun mono(s: String) = text(s, MONO, 8.5f, MUTED)
        fun bullet(s: String) = text("• $s", REGULAR, 9f, MUTED, indent = 6f)
        fun kv(k: String, v: String) = text("$k: $v", REGULAR, 10f, INK)
        fun gap(h: Float) { y -= h }

        fun badge(s: String, colour: Color) {
            ensure(18f)
            val width = s.length * 5.2f + 12f
            stream.setNonStrokingColor(colour)
            stream.addRect(MARGIN, y - 3f, width, 14f)
            stream.fill()
            stream.beginText()
            stream.setFont(BOLD, 9f)
            stream.setNonStrokingColor(Color.WHITE)
            stream.newLineAtOffset(MARGIN + 6f, y + 1f)
            stream.showText(sanitize(s))
            stream.endText()
            y -= 20f
        }

        fun clause(status: String, colour: Color, text: String) {
            ensure(14f)
            stream.setNonStrokingColor(colour)
            stream.addRect(MARGIN, y - 1f, 8f, 8f)
            stream.fill()
            stream.beginText()
            stream.setFont(REGULAR, 9.5f)
            stream.setNonStrokingColor(INK)
            stream.newLineAtOffset(MARGIN + 14f, y)
            stream.showText(sanitize("[$status] " + text.take(MAX_CLAUSE_CHARS)))
            stream.endText()
            y -= 13f
        }

        fun image(image: org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject, w: Float, h: Float) {
            ensure(h + 8f)
            stream.drawImage(image, MARGIN, y - h, w, h)
            y -= h + 8f
        }
    }

    companion object {
        private const val MARGIN = 48f
        private const val MAX_LIST = 12
        private const val MAX_CLAUSE_CHARS = 92
        private const val CHART_WIDTH = 460f
        private const val CHART_HEIGHT = 200f

        private val REGULAR = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        private val BOLD = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        private val MONO = PDType1Font(Standard14Fonts.FontName.COURIER)

        private val INK = Color(0.09f, 0.11f, 0.13f)
        private val MUTED = Color(0.38f, 0.40f, 0.44f)
        private val OK = Color(0.18f, 0.49f, 0.20f)
        private val WARN = Color(0.85f, 0.60f, 0.05f)
        private val BAD = Color(0.78f, 0.16f, 0.16f)

        private fun nowIso(): String =
            DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC).toInstant())

        /** Standard-14 fonts are WinAnsi-only; see PdfSafeText for the transliterations. */
        internal fun sanitize(s: String): String = PdfSafeText.sanitize(s)
    }

    private fun validityText(v: EnvelopeValidity): String = when (v) {
        EnvelopeValidity.VALID -> "VALID: controlled start, enough iterations"
        EnvelopeValidity.PRECONDITIONS_UNMET -> "PRECONDITIONS UNMET: not comparable with other runs"
        EnvelopeValidity.NO_PRECONDITION_GATE -> "NO GATE: start conditions recorded but not controlled"
        EnvelopeValidity.INSUFFICIENT_DATA -> "INSUFFICIENT DATA: too few iterations for sustained load"
    }
}
