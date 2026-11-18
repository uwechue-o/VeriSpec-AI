package ai.verispec.core.domain.usecase

import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.model.VerificationReport
import ai.verispec.core.domain.port.AttestationVerifier
import ai.verispec.core.domain.port.Canonicalizer
import ai.verispec.core.domain.port.Hasher
import ai.verispec.core.domain.port.SignatureVerifier

/**
 * Independent verification of a foreign bundle. Uses only the bundle content and
 * public certificates, never producer-side state, so any third party can run it.
 */
class VerifyBundleUseCase(
    private val canonicalizer: Canonicalizer,
    private val hasher: Hasher,
    private val signatures: SignatureVerifier,
    private val attestation: AttestationVerifier,
) {
    operator fun invoke(bundle: EvidenceBundle): VerificationReport {
        val issues = mutableListOf<String>()

        val schemaOk = bundle.schemaVersion.major == EvidenceBundle.CURRENT_SCHEMA_VERSION.major
        if (!schemaOk) issues += "Unsupported schema version ${bundle.schemaVersion}"

        val bytes = canonicalizer.bundleBytes(bundle.unsigned())
        val hashOk = hasher.sha256(bytes).hex == bundle.bundleId.value
        if (!hashOk) issues += "bundleId does not match canonical content hash: bundle was altered after signing"

        val manifestHashOk = hasher.sha256(canonicalizer.manifestBytes(bundle.manifest)).hex == bundle.manifest.manifestId.value
        if (!manifestHashOk) issues += "manifestId does not match canonical manifest hash"

        val leaf = bundle.attestationChain.firstOrNull()
        val sigOk = if (leaf == null) {
            issues += "No attestation chain: signature cannot be tied to a key"
            false
        } else {
            runCatching { signatures.verify(bytes, bundle.signature, leaf) }
                .onFailure { issues += "Signature verification threw: ${it.message}" }
                .getOrDefault(false)
                .also { if (!it) issues += "Detached signature does not verify against the leaf certificate" }
        }

        val chain = if (bundle.attestationChain.isEmpty()) {
            AttestationVerifier.ChainResult(false, false, false, null, listOf("empty chain"))
        } else {
            attestation.verifyChain(bundle.attestationChain)
        }
        issues += chain.issues

        val expectedChallenge = Sha256(bundle.manifest.manifestId.value)
        val challengeOk = chain.challenge == expectedChallenge
        if (!challengeOk) issues += "Attestation challenge is not bound to this manifest"

        if (bundle.signature.hardwareBacked != chain.hardwareBacked) {
            issues += "Signature claims hardwareBacked=${bundle.signature.hardwareBacked} but chain says ${chain.hardwareBacked}"
        }

        return VerificationReport(
            bundleId = bundle.bundleId,
            canonicalHashMatches = hashOk && manifestHashOk,
            signatureValid = sigOk,
            attestationChainValid = chain.chainValid,
            hardwareBacked = chain.hardwareBacked,
            strongBox = chain.strongBox,
            challengeBoundToManifest = challengeOk,
            schemaVersionSupported = schemaOk,
            issues = issues.distinct(),
        )
    }
}
