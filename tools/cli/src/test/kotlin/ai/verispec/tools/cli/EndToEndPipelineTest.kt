package ai.verispec.tools.cli

import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.DetachedSignature
import ai.verispec.core.domain.model.EncodedCertificate
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.EvidenceFacts
import ai.verispec.core.domain.model.RegulatoryFramework
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.model.UnsignedBundle
import ai.verispec.core.domain.usecase.MapClausesUseCase
import ai.verispec.core.evidence.BundleCodec
import ai.verispec.core.evidence.EvidenceCanonicalizer
import ai.verispec.core.evidence.Hashing
import ai.verispec.tools.analysis.ComputeDecayUseCase
import ai.verispec.tools.analysis.ComputeDivergenceUseCase
import ai.verispec.tools.analysis.TestBundles
import ai.verispec.tools.report.JsonReportRenderer
import ai.verispec.tools.report.PdfReportRenderer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Exercises the whole spine on the JVM: clause mapping → canonical bytes →
 * real ECDSA signature → decode from disk → verify → divergence → PDF + JSON.
 *
 * The signature here is a plain JCA key rather than a Keystore key, so the
 * attestation chain is absent and verification must report that honestly while
 * still confirming the signature itself.
 */
class EndToEndPipelineTest : StringSpec({

    "clause mapping produces coverage with named gaps" {
        val catalog = ai.verispec.core.clauses.YamlClauseCatalog()
        val bundle = TestBundles.bundle("device-a", "Tensor G3")
        val facts = EvidenceFacts.derive(
            manifest = bundle.manifest,
            device = bundle.device,
            provenance = bundle.provenance,
            observations = bundle.observations,
            signaturePlanned = true,
            hardwareAttestationPlanned = true,
            divergenceReportAttached = false,
            quantizationDeltaAttached = false,
        )
        val coverage = runBlocking { MapClausesUseCase(catalog)(facts) }
        coverage.shouldNotBeEmpty()
        coverage.map { it.framework }.toSet() shouldBe RegulatoryFramework.entries.toSet()
        // Divergence is not attached, so at least one clause must be flagged.
        coverage.count { it.status != ai.verispec.core.domain.model.CoverageStatus.SATISFIED } shouldBeGreaterThan 0
        coverage.filter { it.status != ai.verispec.core.domain.model.CoverageStatus.SATISFIED }
            .forEach { it.gapDescription.shouldNotBeNull() }
    }

    "a really signed bundle round-trips through disk and verifies" {
        val signed = SignedFixture.build("device-a", "Tensor G3")
        val text = BundleCodec.encodePretty(signed.bundle)
        val decoded = BundleCodec.decode(text)
        decoded shouldBe signed.bundle

        val report = verifier(emptySet())(decoded)
        report.canonicalHashMatches.shouldBeTrue()
        report.signatureValid.shouldBeTrue()
        // No Keystore attestation extension on a plain JCA key: this must be reported, not hidden.
        report.attestationChainValid shouldBe false
        report.isTrustworthy shouldBe false
        report.issues.any { it.contains("attestation", ignoreCase = true) }.shouldBeTrue()
    }

    "tampering with a signed bundle is detected" {
        val signed = SignedFixture.build("device-a", "Tensor G3")
        val tampered = signed.bundle.copy(
            observations = signed.bundle.observations.copy(
                iterations = signed.bundle.observations.iterations.map { it.copy(decodeTokensPerSecond = 999.0) },
            ),
        )
        val report = verifier(emptySet())(tampered)
        report.canonicalHashMatches shouldBe false
        report.signatureValid shouldBe false
    }

    "PDF and analysis JSON render from a two-device fleet" {
        val a = SignedFixture.build("device-a", "Tensor G3").bundle
        val b = SignedFixture.build("device-b", "Snapdragon 8 Gen 3", driftFromIteration = 6).bundle

        val divergence = ComputeDivergenceUseCase()(listOf(a, b))
        divergence.bitIdenticalAcrossFleet shouldBe false

        val decay = ComputeDecayUseCase()(a)
        decay.shouldNotBeNull()

        val pdf = PdfReportRenderer().render(PdfReportRenderer.Input(a, decay, verifier(emptySet())(a), divergence))
        pdf.size shouldBeGreaterThan 1000
        String(pdf.copyOfRange(0, 5), Charsets.ISO_8859_1) shouldBe "%PDF-"

        val renderer = JsonReportRenderer()
        val json = renderer.renderPretty(renderer.build(a, decay, verifier(emptySet())(a), divergence))
        json shouldContain "ai.verispec.analysis-report"
        json shouldContain "bitIdenticalAcrossFleet"
    }
})

/** Builds a bundle signed with a real, ephemeral EC key so signature checks are genuine. */
object SignedFixture {

    data class Result(val bundle: EvidenceBundle)

    fun build(
        deviceId: String,
        socModel: String,
        driftFromIteration: Int? = null,
        clauseCoverage: List<ai.verispec.core.domain.model.ClauseCoverage> = emptyList(),
    ): Result {
        val base = TestBundles.bundle(deviceId, socModel, driftFromIteration = driftFromIteration, driftMagnitude = 0.02f)
        // Sign the FINAL content: anything added after signing invalidates the
        // signature, which is exactly what the tamper test relies on.
        val unsigned = UnsignedBundle(
            schemaVersion = base.schemaVersion,
            manifest = base.manifest,
            device = base.device,
            provenance = base.provenance,
            observations = base.observations,
            clauseCoverage = clauseCoverage,
        )
        val canonical = EvidenceCanonicalizer.bundleBytes(unsigned)

        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val signatureBytes = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(canonical)
        }.sign()

        val leaf = SelfSignedCertificates.create(keyPair)

        return Result(
            EvidenceBundle(
                bundleId = BundleId(Hashing.sha256(canonical).hex),
                schemaVersion = unsigned.schemaVersion,
                manifest = unsigned.manifest,
                device = unsigned.device,
                provenance = unsigned.provenance,
                observations = unsigned.observations,
                clauseCoverage = unsigned.clauseCoverage,
                signature = DetachedSignature(
                    algorithm = "SHA256withECDSA",
                    signature = signatureBytes,
                    keyId = Hashing.sha256(keyPair.public.encoded),
                    signedAtEpochMillis = 1_760_000_020_000L,
                    hardwareBacked = false,
                    strongBox = false,
                    attestationChallenge = Sha256(unsigned.manifest.manifestId.value),
                ),
                attestationChain = listOf(EncodedCertificate(leaf)),
            ),
        )
    }
}
