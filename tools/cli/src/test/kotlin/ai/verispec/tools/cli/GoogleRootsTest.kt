package ai.verispec.tools.cli

import ai.verispec.core.domain.usecase.VerifyBundleUseCase
import ai.verispec.core.evidence.BundleCodec
import ai.verispec.core.evidence.EvidenceCanonicalizer
import ai.verispec.core.evidence.Hashing
import ai.verispec.core.evidence.verify.AttestationChainVerifier
import ai.verispec.core.evidence.verify.JcaSignatureVerifier
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * Real bundles from three phones (2026-11-14 cross-device run, shipped as the
 * manuals' chart data) must verify against Google's pinned roots, and must stop
 * verifying when those roots are not the ones trusted.
 */
class GoogleRootsTest : StringSpec({

    val names = listOf(
        "pixel6-2026-11-14.json",
        "honor-magic-v3-2026-11-14.json",
        "samsung-tab-s7plus-2026-11-14.json",
    )

    fun load(name: String) = BundleCodec.decode(
        GoogleRootsTest::class.java.getResourceAsStream("/docs/cross-device-2026-11-14/$name")!!.readBytes(),
    )

    names.forEach { name ->
        "$name chains to a pinned Google root and is trustworthy" {
            val report = verifier(emptySet())(load(name))
            report.issues shouldBe emptyList()
            report.attestationChainValid.shouldBeTrue()
            report.hardwareBacked.shouldBeTrue()
            report.isTrustworthy.shouldBeTrue()
        }
    }

    "a genuine chain is rejected when its root is not trusted" {
        val onlyOtherRoot = VerifyBundleUseCase(
            EvidenceCanonicalizer,
            Hashing,
            JcaSignatureVerifier(),
            AttestationChainVerifier(trustedRootSpkiSha256 = setOf("00".repeat(32))),
        )
        val report = onlyOtherRoot(load(names.first()))
        report.attestationChainValid shouldBe false
        report.isTrustworthy shouldBe false
        report.issues.any { it.contains("not a trusted attestation root") }.shouldBeTrue()
    }
})
