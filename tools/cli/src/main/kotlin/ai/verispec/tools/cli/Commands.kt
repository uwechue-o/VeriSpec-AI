package ai.verispec.tools.cli

import ai.verispec.core.domain.model.acceleratorSummary
import ai.verispec.core.domain.model.CoverageStatus
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.HardwareSummary
import ai.verispec.core.domain.model.Workload
import ai.verispec.core.domain.usecase.VerifyBundleUseCase
import ai.verispec.core.evidence.BundleCodec
import ai.verispec.core.evidence.EvidenceCanonicalizer
import ai.verispec.core.evidence.Hashing
import ai.verispec.core.evidence.verify.AttestationChainVerifier
import ai.verispec.core.evidence.verify.JcaSignatureVerifier
import ai.verispec.tools.analysis.ComputeDecayUseCase
import ai.verispec.tools.analysis.ComputeDivergenceUseCase
import ai.verispec.tools.report.JsonReportRenderer
import ai.verispec.tools.report.PdfReportRenderer
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File

internal val terminal = Terminal()

internal fun loadBundle(file: File): EvidenceBundle =
    BundleCodec.decode(file.readText())

/** Google's attestation roots are always trusted; [extraRoots] adds more (e.g. a test lab's own CA). */
internal fun verifier(extraRoots: Set<String>) = VerifyBundleUseCase(
    canonicalizer = EvidenceCanonicalizer,
    hasher = Hashing,
    signatures = JcaSignatureVerifier(),
    attestation = AttestationChainVerifier(
        trustedRootSpkiSha256 = AttestationChainVerifier.GOOGLE_ROOT_SPKI_SHA256 + extraRoots.map { it.lowercase() },
    ),
)

private fun ok(text: String) = TextColors.green(text)
private fun bad(text: String) = TextColors.red(text)
private fun warn(text: String) = TextColors.yellow(text)
private fun mark(flag: Boolean, good: String = "yes", evil: String = "NO") = if (flag) ok(good) else bad(evil)

/* ------------------------------------------------------------------ verify */

class VerifyCommand : CliktCommand(name = "verify") {
    override fun help(context: Context) =
        "Verify a bundle's canonical hash, signature and attestation chain. Uses only the file and public certificates."

    private val files by argument("bundle").file(mustExist = true, canBeDir = false).multiple(required = true)
    private val trustedRoots by option("--trusted-root", help = "SHA-256 (hex) of an extra attestation root's SubjectPublicKeyInfo to trust besides Google's. Repeatable.")
        .multiple()
    private val strict by option("--strict", help = "Exit non-zero unless every bundle is fully trustworthy.").flag()

    override fun run() {
        val verify = verifier(trustedRoots.toSet())
        var allTrustworthy = true

        files.forEach { file ->
            val bundle = loadBundle(file)
            val report = verify(bundle)
            allTrustworthy = allTrustworthy && report.isTrustworthy

            terminal.println(TextStyles.bold("${file.name}  ${bundle.bundleId.short}…"))
            terminal.println(
                table {
                    body {
                        row("canonical hash", mark(report.canonicalHashMatches, "matches", "MISMATCH"))
                        row("signature", mark(report.signatureValid, "valid", "INVALID"))
                        row("attestation chain", mark(report.attestationChainValid, "valid", "INVALID"))
                        row("hardware-backed", mark(report.hardwareBacked))
                        row("StrongBox", if (report.strongBox) ok("yes") else warn("no"))
                        row("challenge bound to manifest", mark(report.challengeBoundToManifest))
                        row("schema supported", mark(report.schemaVersionSupported))
                        row("device", "${bundle.device.manufacturer} ${bundle.device.deviceModel} (${bundle.device.soc.model})")
                        row("verdict", if (report.isTrustworthy) ok("TRUSTWORTHY") else bad("NOT TRUSTWORTHY"))
                    }
                },
            )
            report.issues.forEach { terminal.println("  " + warn("• $it")) }
            terminal.println()
        }

        if (strict && !allTrustworthy) throw com.github.ajalt.clikt.core.ProgramResult(1)
    }
}

/* ------------------------------------------------------------------ diverge */

class DivergeCommand : CliktCommand(name = "diverge") {
    override fun help(context: Context) =
        "Cross-device numerical divergence over two or more bundles of the same manifest. Runs on the host: it cannot run on any single device."

    private val files by argument("bundles").file(mustExist = true, canBeDir = false).multiple(required = true)
    private val reference by option("--reference", help = "Bundle id to use as the baseline (default: first file).")
    private val jsonOut by option("--json", help = "Write the analysis document here.").file(canBeDir = false)

    override fun run() {
        if (files.size < 2) {
            terminal.println(bad("Divergence needs at least two bundles."))
            throw com.github.ajalt.clikt.core.ProgramResult(2)
        }
        val bundles = files.map(::loadBundle)
        val report = try {
            ComputeDivergenceUseCase()(bundles, reference)
        } catch (e: ComputeDivergenceUseCase.IncomparableBundlesException) {
            terminal.println(bad(e.message ?: "bundles are not comparable"))
            throw com.github.ajalt.clikt.core.ProgramResult(2)
        }

        terminal.println(TextStyles.bold("Manifest ${report.manifestId.take(16)}…  ${report.label}"))
        terminal.println("Reference: ${report.reference.shortLabel} (${report.reference.bundleId.take(12)}…)")
        terminal.println(
            table {
                header { row("device", "delegate", "runtime", "digest agreement", "first diverge", "mean cosine", "max L∞") }
                body {
                    report.pairwise.forEach { d ->
                        row(
                            d.candidate.socModel,
                            d.candidate.delegate.name,
                            d.candidate.runtimeVersionObserved ?: d.candidate.runtime.name,
                            "%.2f %%".format(d.digestAgreementRate * 100).let { if (d.digestAgreementRate >= 1.0) ok(it) else bad(it) },
                            d.firstDivergentIteration?.toString() ?: "-",
                            d.meanCosineSimilarity?.let { "%.6f".format(it) } ?: "n/a",
                            d.maxLInfDelta?.let { "%.6f".format(it) } ?: "n/a",
                        )
                    }
                }
            },
        )
        if (report.bitIdenticalAcrossFleet) {
            terminal.println(ok("Outputs are byte-identical across every device compared."))
        } else {
            terminal.println(bad("Outputs diverge across devices. Worst agreement: %.2f %%".format(report.worstAgreement * 100)))
        }
        report.warnings.forEach { terminal.println(warn("• $it")) }

        jsonOut?.let { out ->
            val renderer = JsonReportRenderer()
            val doc = renderer.build(bundles.first(), ComputeDecayUseCase()(bundles.first()), null, report)
            out.writeText(renderer.renderPretty(doc))
            terminal.println("Wrote ${out.path}")
        }
    }
}

/* ------------------------------------------------------------------ report */

class ReportCommand : CliktCommand(name = "report") {
    override fun help(context: Context) =
        "Render a bundle to PDF (submission binder) and JSON (machine-readable analysis)."

    private val bundleFile by argument("bundle").file(mustExist = true, canBeDir = false)
    private val compare by option("--compare", help = "Additional bundles of the same manifest, for a divergence section.")
        .file(mustExist = true, canBeDir = false).multiple()
    private val pdfOut by option("--pdf", help = "PDF output path.").file(canBeDir = false)
    private val jsonOut by option("--json", help = "Analysis JSON output path.").file(canBeDir = false)
    private val trustedRoots by option("--trusted-root").multiple()
    private val skipVerify by option("--no-verify", help = "Do not verify before rendering.").flag()

    override fun run() {
        val bundle = loadBundle(bundleFile)
        val verification = if (skipVerify) null else verifier(trustedRoots.toSet())(bundle)
        val decay = ComputeDecayUseCase()(bundle)
        val divergence = if (compare.isEmpty()) {
            null
        } else {
            runCatching { ComputeDivergenceUseCase()(listOf(bundle) + compare.map(::loadBundle), bundle.bundleId.value) }
                .onFailure { terminal.println(warn("Divergence skipped: ${it.message}")) }
                .getOrNull()
        }

        val pdfPath = pdfOut ?: File(bundleFile.parentFile, "verispec-report-${bundle.bundleId.short}.pdf")
        pdfPath.writeBytes(PdfReportRenderer().render(PdfReportRenderer.Input(bundle, decay, verification, divergence)))
        terminal.println("Wrote ${pdfPath.path}")

        val renderer = JsonReportRenderer()
        val doc = renderer.build(bundle, decay, verification, divergence)
        val jsonPath = jsonOut ?: File(bundleFile.parentFile, "verispec-analysis-${bundle.bundleId.short}.json")
        jsonPath.writeText(renderer.renderPretty(doc))
        terminal.println("Wrote ${jsonPath.path}")

        val gaps = bundle.clauseCoverage.count { it.status != CoverageStatus.SATISFIED }
        if (gaps > 0) terminal.println(warn("$gaps clause(s) are not fully evidenced by this bundle."))
    }
}

/* ------------------------------------------------------------------ inspect */

class InspectCommand : CliktCommand(name = "inspect") {
    override fun help(context: Context) = "Print a bundle's provenance, envelope and clause coverage."

    private val bundleFile by argument("bundle").file(mustExist = true, canBeDir = false)

    override fun run() {
        val b = loadBundle(bundleFile)
        val decay = ComputeDecayUseCase()(b)
        val unit = if (b.manifest.workload is Workload.TensorInference) "inf/s" else "tok/s"
        terminal.println(TextStyles.bold(b.manifest.label.ifBlank { "Unlabelled run" }))
        terminal.println(
            table {
                body {
                    row("bundle", b.bundleId.value)
                    row("manifest", b.manifest.manifestId.value)
                    row("model sha256", b.provenance.modelSha256.hex)
                    row("format / quant", "${b.provenance.modelFormat} · ${b.manifest.model.quantization.scheme}")
                    row("runtime", "${b.provenance.runtime} ${b.provenance.runtimeVersionDeclared} (observed ${b.provenance.runtimeVersionObserved ?: "n/a"})")
                    row("delegate", "${b.provenance.delegate} → ${b.provenance.delegateActuallyUsed}")
                    b.provenance.acceleratorSummary()?.let { row("nnapi device", it) }
                    row("npu driver", b.provenance.npuDriverVersion ?: "not reported")
                    row("gpu driver", b.provenance.gpuDriverVersion ?: "not reported")
                    row("device", "${b.device.manufacturer} ${b.device.deviceModel} · ${b.device.soc.vendor} ${b.device.soc.model}")
                    row("android", "${b.device.androidRelease} (API ${b.device.apiLevel}), patch ${b.device.securityPatch}")
                    row("iterations", b.observations.iterations.size.toString())
                    row("peak $unit", decay?.let { "%.2f".format(it.peakTokensPerSecond) } ?: "-")
                    row("steady $unit", decay?.let { d -> d.steadyStateTokensPerSecond?.let { "%.2f".format(it) } ?: "not settled" } ?: "-")
                    row("decay", decay?.let { d -> d.decayPercent?.let { "%.1f %%".format(it) } ?: "n/a" } ?: "-")
                    row("validity", decay?.validity?.name ?: "-")
                    row("throttled", decay?.let { "%.0f %%".format(it.throttledFraction * 100) } ?: "-")
                    HardwareSummary.of(b.observations)?.rows()?.forEach { (k, v) -> row(k, v) }
                }
            },
        )
        if (b.clauseCoverage.isNotEmpty()) {
            terminal.println(
                table {
                    header { row("framework", "clause", "status", "gap") }
                    body {
                        b.clauseCoverage.sortedBy { it.status.ordinal * -1 }.forEach { c ->
                            row(
                                c.framework.name,
                                c.reference,
                                when (c.status) {
                                    CoverageStatus.SATISFIED -> ok("satisfied")
                                    CoverageStatus.PARTIAL -> warn("partial")
                                    CoverageStatus.NOT_COVERED -> bad("not covered")
                                },
                                c.gapDescription?.take(60) ?: "",
                            )
                        }
                    }
                },
            )
        }
        b.provenance.notes.forEach { terminal.println("• $it") }
    }
}
