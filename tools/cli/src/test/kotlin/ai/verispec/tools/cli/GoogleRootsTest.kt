package ai.verispec.tools.cli

import ai.verispec.core.domain.model.RootOfTrust
import ai.verispec.core.domain.model.TrustedApps
import ai.verispec.core.domain.model.VerifiedBootState
import ai.verispec.core.domain.port.AttestationVerifier
import ai.verispec.core.domain.usecase.VerifyBundleUseCase
import ai.verispec.core.evidence.BundleCodec
import ai.verispec.core.evidence.EvidenceCanonicalizer
import ai.verispec.core.evidence.Hashing
import ai.verispec.core.evidence.verify.AttestationChainVerifier
import ai.verispec.core.evidence.verify.JcaSignatureVerifier
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Real bundles from four phones (the manuals' chart data) must verify against
 * Google's pinned roots, from a locked, verified boot, made by a pinned VeriSpec
 * build; and must stop verifying when any of those is not what is trusted.
 */
class GoogleRootsTest : StringSpec({

    val names = listOf(
        "cross-device-2026-11-14/pixel6-2026-11-12.json",
        "cross-device-2026-11-14/pixel6-2026-11-14.json",
        "cross-device-2026-11-14/honor-magic-v3-2026-11-14.json",
        "cross-device-2026-11-14/samsung-tab-s7plus-2026-11-14.json",
        "cross-device-2026-11-14/samsung-tab-s7plus-2026-11-14-repeat.json",
        "long-run-2026-11-16/pixel6-mediapipe-qwen-2026-11-16.json",
        "accelerators-2026-11-18/oneplus8t-litert-cpu.json",
        "accelerators-2026-11-18/oneplus8t-litert-nnapi.json",
        "accelerators-2026-11-18/oneplus8t-litert-nnapi-qti-default.json",
        "accelerators-2026-11-18/oneplus8t-litert-nnapi-qti-gpu.json",
        "accelerators-2026-11-18/oneplus8t-litert-nnapi-qti-hta.json",
        "accelerators-2026-11-18/oneplus8t-mediapipe-cpu.json",
        "accelerators-2026-11-18/pixel6-mediapipe-cpu.json",
        "accelerators-2026-11-18/tab-s7plus-litert-cpu.json",
        "accelerators-2026-11-18/tab-s7plus-litert-nnapi.json",
        "accelerators-2026-11-18/tab-s7plus-mediapipe-cpu.json",
    )

    fun load(name: String) = BundleCodec.decode(
        GoogleRootsTest::class.java.getResourceAsStream("/docs/$name")!!.readBytes(),
    )

    fun verifyWith(chain: AttestationVerifier, apps: TrustedApps = AttestationChainVerifier.VERISPEC_APPS) =
        VerifyBundleUseCase(EvidenceCanonicalizer, Hashing, JcaSignatureVerifier(), chain, apps)

    val google = AttestationChainVerifier(trustedRootSpkiSha256 = AttestationChainVerifier.GOOGLE_ROOT_SPKI_SHA256)

    /** The genuine chain result with its root of trust replaced. */
    fun withBoot(root: RootOfTrust?) = object : AttestationVerifier {
        override fun verifyChain(chain: List<ai.verispec.core.domain.model.EncodedCertificate>) =
            google.verifyChain(chain).copy(rootOfTrust = root)
    }

    names.forEach { name ->
        "$name chains to a pinned Google root, booted verified and came from a pinned VeriSpec build" {
            val report = verifier()(load(name))
            report.issues shouldBe emptyList()
            report.attestationChainValid.shouldBeTrue()
            report.hardwareBacked.shouldBeTrue()
            report.rootOfTrust shouldBe RootOfTrust(deviceLocked = true, verifiedBootState = VerifiedBootState.VERIFIED)
            report.verifiedBoot.shouldBeTrue()
            report.attestedApp!!.packageNames shouldBe listOf("ai.verispec.app.debug")
            report.appIdentityTrusted.shouldBeTrue()
            report.isTrustworthy.shouldBeTrue()
        }
    }

    "a genuine chain is rejected when its root is not trusted" {
        val report = verifyWith(AttestationChainVerifier(trustedRootSpkiSha256 = setOf("00".repeat(32))))(load(names.first()))
        report.attestationChainValid shouldBe false
        report.isTrustworthy shouldBe false
        report.issues.any { it.contains("not a trusted attestation root") }.shouldBeTrue()
    }

    "a genuine key made by an app signed with another key is not trustworthy" {
        val report = verifyWith(google, TrustedApps(setOf("ai.verispec.app.debug"), emptySet()))(load(names.first()))
        report.signatureValid.shouldBeTrue()
        report.attestationChainValid.shouldBeTrue()
        report.appIdentityTrusted shouldBe false
        report.isTrustworthy shouldBe false
        report.issues.any { it.contains("not a trusted VeriSpec build") }.shouldBeTrue()
    }

    "a genuine key made by another package is not trustworthy, even with a trusted signer" {
        val apps = AttestationChainVerifier.VERISPEC_APPS.copy(packageNames = setOf("ai.verispec.app"))
        verifyWith(google, apps)(load(names.first())).appIdentityTrusted shouldBe false
    }

    "an unlocked bootloader, a non-verified boot or a missing root of trust is not trustworthy" {
        listOf(
            RootOfTrust(deviceLocked = false, verifiedBootState = VerifiedBootState.VERIFIED) to "unlocked",
            RootOfTrust(deviceLocked = true, verifiedBootState = VerifiedBootState.SELF_SIGNED) to "SELF_SIGNED",
            null to "boot state is unknown",
        ).forEach { (root, issue) ->
            val report = verifyWith(withBoot(root))(load(names.first()))
            report.verifiedBoot shouldBe false
            report.isTrustworthy shouldBe false
            report.issues.any { it.contains(issue) }.shouldBeTrue()
        }
    }

    "a genuine key made for another manifest is not trustworthy" {
        val bundle = load(names.first())
        val other = load(names.first { it.startsWith("long-run") })
        other.manifest.manifestId shouldNotBe bundle.manifest.manifestId
        val report = verifyWith(google)(bundle.copy(attestationChain = other.attestationChain))
        report.challengeBoundToManifest shouldBe false
        report.isTrustworthy shouldBe false
    }

    "--trusted-signer takes keytool's colon form" {
        signerDigest("0E:A6:F6:74:BF:93:38:69:FD:EC:0A:9A:FF:9F:8D:E1:91:CB:87:0D:D4:AB:9E:51:AB:33:57:97:B6:01:A3:04") shouldBe
            AttestationChainVerifier.VERISPEC_APPS.signerDigests.single()
    }
})
