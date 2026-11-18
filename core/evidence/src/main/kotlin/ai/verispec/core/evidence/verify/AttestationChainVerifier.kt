package ai.verispec.core.evidence.verify

import ai.verispec.core.domain.model.EncodedCertificate
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.port.AttestationVerifier
import ai.verispec.core.evidence.Hashing
import java.security.cert.X509Certificate

/**
 * Validates an Android Key Attestation chain with plain JCA + a tiny DER reader:
 *
 *  1. every certificate is signed by the next one, the last is self-signed;
 *  2. the leaf carries the Android attestation extension; its KeyDescription
 *     yields the security level (TEE / StrongBox) and the challenge;
 *  3. optionally, the root's SubjectPublicKeyInfo hash is in [trustedRootSpkiSha256].
 *
 * Without a trusted root set the chain is only self-consistent; the report says
 * so explicitly instead of pretending. Google publishes its hardware attestation
 * root; pass its SPKI SHA-256 to make the check binding.
 */
class AttestationChainVerifier(
    private val trustedRootSpkiSha256: Set<String> = emptySet(),
    private val checkValidityPeriod: Boolean = false,
) : AttestationVerifier {

    override fun verifyChain(chain: List<EncodedCertificate>): AttestationVerifier.ChainResult {
        val issues = mutableListOf<String>()
        val certs: List<X509Certificate> = try {
            chain.map { JcaSignatureVerifier.parse(it) }
        } catch (e: Exception) {
            return AttestationVerifier.ChainResult(false, false, false, null, listOf("Unparseable certificate: ${e.message}"))
        }
        if (certs.isEmpty()) return AttestationVerifier.ChainResult(false, false, false, null, listOf("Empty chain"))

        var linksOk = true
        for (i in certs.indices) {
            val cert = certs[i]
            val issuer = certs.getOrElse(i + 1) { cert } // root verifies against itself
            try {
                cert.verify(issuer.publicKey)
                if (checkValidityPeriod) cert.checkValidity()
            } catch (e: Exception) {
                linksOk = false
                issues += "Certificate $i failed verification against its issuer: ${e.message}"
            }
        }

        val rootSpki = Hashing.sha256(certs.last().publicKey.encoded).hex
        val rootTrusted = when {
            trustedRootSpkiSha256.isEmpty() -> {
                issues += "No trusted attestation roots configured; chain is self-consistent only"
                true
            }
            rootSpki in trustedRootSpkiSha256 -> true
            else -> {
                issues += "Chain root ${rootSpki.take(12)}… is not a trusted attestation root"
                false
            }
        }

        val description = parseKeyDescription(certs.first())
        if (description == null) issues += "Leaf certificate carries no Android key attestation extension"

        val keymasterLevel = description?.keymasterSecurityLevel ?: -1
        return AttestationVerifier.ChainResult(
            chainValid = linksOk && rootTrusted && description != null,
            hardwareBacked = keymasterLevel >= SECURITY_LEVEL_TEE,
            strongBox = keymasterLevel == SECURITY_LEVEL_STRONGBOX,
            challenge = description?.challenge,
            issues = issues,
        )
    }

    data class KeyDescription(
        val attestationVersion: Long,
        val attestationSecurityLevel: Long,
        val keymasterVersion: Long,
        val keymasterSecurityLevel: Long,
        val challenge: Sha256?,
        val challengeRaw: ByteArray,
    )

    fun parseKeyDescription(leaf: X509Certificate): KeyDescription? {
        val extension = leaf.getExtensionValue(ATTESTATION_OID) ?: return null
        return try {
            // Extension value is an OCTET STRING wrapping the DER KeyDescription SEQUENCE.
            val outer = Der(extension).read()
            val seq = Der(outer.content).read()
            require(seq.isSequence) { "KeyDescription is not a SEQUENCE" }
            val fields = seq.children()
            require(fields.size >= 5) { "KeyDescription too short" }
            val challengeBytes = fields[4].also { require(it.isOctetString) { "challenge is not an OCTET STRING" } }.content
            KeyDescription(
                attestationVersion = fields[0].asInt(),
                attestationSecurityLevel = fields[1].asInt(),
                keymasterVersion = fields[2].asInt(),
                keymasterSecurityLevel = fields[3].asInt(),
                challenge = challengeBytes.takeIf { it.size == 32 }?.let { Sha256.fromBytes(it) },
                challengeRaw = challengeBytes,
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /**
         * SPKI SHA-256 of Google's hardware attestation roots, as published at
         * https://android.googleapis.com/attestation/root (fetched 2026-11-16).
         * Pinned by key, not certificate: the RSA key has been reissued under
         * several root certificates (2016, 2019, 2022) and all of them match.
         * Public values; anyone can recompute them from Google's list.
         */
        val GOOGLE_ROOT_SPKI_SHA256: Set<String> = setOf(
            // RSA 4096, serialNumber=f92009e853b6b045
            "feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae",
            // ECDSA P-384, CN=Key Attestation CA1 (2025-2035)
            "3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec",
        )

        const val ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"
        const val SECURITY_LEVEL_SOFTWARE = 0L
        const val SECURITY_LEVEL_TEE = 1L
        const val SECURITY_LEVEL_STRONGBOX = 2L
    }
}
