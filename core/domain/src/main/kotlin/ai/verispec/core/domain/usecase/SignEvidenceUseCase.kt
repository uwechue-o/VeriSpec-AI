package ai.verispec.core.domain.usecase

import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.port.EvidenceSigner

/**
 * Hardware-backed signature + attestation chain. The attestation challenge is
 * the manifest hash, so the chain proves which run the key was created for.
 */
class SignEvidenceUseCase(private val signer: EvidenceSigner) {

    suspend operator fun invoke(assembled: AssembleEvidenceUseCase.Assembled): EvidenceBundle {
        val challenge = Sha256(assembled.unsigned.manifest.manifestId.value)
        val signature = signer.sign(assembled.canonicalBytes, challenge)
        val chain = signer.attestationChain(challenge)
        val u = assembled.unsigned
        return EvidenceBundle(
            bundleId = assembled.bundleId,
            schemaVersion = u.schemaVersion,
            manifest = u.manifest,
            device = u.device,
            provenance = u.provenance,
            observations = u.observations,
            clauseCoverage = u.clauseCoverage,
            signature = signature,
            attestationChain = chain,
        )
    }
}
