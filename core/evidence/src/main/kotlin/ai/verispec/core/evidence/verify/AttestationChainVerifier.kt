package ai.verispec.core.evidence.verify

import ai.verispec.core.domain.model.AttestedApp
import ai.verispec.core.domain.model.EncodedCertificate
import ai.verispec.core.domain.model.RootOfTrust
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.model.TrustedApps
import ai.verispec.core.domain.model.VerifiedBootState
import ai.verispec.core.domain.port.AttestationVerifier
import ai.verispec.core.evidence.Hashing
import java.security.cert.X509Certificate

/**
 * Validates an Android Key Attestation chain with plain JCA + a tiny DER reader:
 *
 *  1. every certificate is signed by the next one, the last is self-signed;
 *  2. the leaf carries the Android attestation extension; its KeyDescription
 *     yields the security level (TEE / StrongBox) and the challenge;
 *  3. optionally, the root's SubjectPublicKeyInfo hash is in [trustedRootSpkiSha256];
 *  4. the KeyDescription's authorization lists give the hardware-enforced root of
 *     trust (bootloader lock, verified-boot state) and the app the key was made for.
 *     The caller judges those; this class only reads them.
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
            // A software key's "hardware-enforced" list is written by software, so it proves nothing.
            rootOfTrust = description?.rootOfTrust?.takeIf { keymasterLevel >= SECURITY_LEVEL_TEE },
            attestedApp = description?.attestedApp,
        )
    }

    data class KeyDescription(
        val attestationVersion: Long,
        val attestationSecurityLevel: Long,
        val keymasterVersion: Long,
        val keymasterSecurityLevel: Long,
        val challenge: Sha256?,
        val challengeRaw: ByteArray,
        /** From the hardware-enforced list only; a software-enforced copy proves nothing. */
        val rootOfTrust: RootOfTrust? = null,
        /** From the software-enforced list, where Android's Keystore service puts it. */
        val attestedApp: AttestedApp? = null,
    )

    fun parseKeyDescription(leaf: X509Certificate): KeyDescription? =
        leaf.getExtensionValue(ATTESTATION_OID)?.let(::parseKeyDescription)

    /** Parses the extension value as [X509Certificate.getExtensionValue] returns it. */
    fun parseKeyDescription(extension: ByteArray): KeyDescription? = try {
        // Extension value is an OCTET STRING wrapping the DER KeyDescription SEQUENCE.
        val outer = Der(extension).read()
        val seq = Der(outer.content).read()
        require(seq.isSequence) { "KeyDescription is not a SEQUENCE" }
        val fields = seq.children()
        require(fields.size >= 5) { "KeyDescription too short" }
        val challengeBytes = fields[4].also { require(it.isOctetString) { "challenge is not an OCTET STRING" } }.content
        val softwareEnforced = fields.getOrNull(6)?.let(::authorizations).orEmpty()
        val hardwareEnforced = fields.getOrNull(7)?.let(::authorizations).orEmpty()
        KeyDescription(
            attestationVersion = fields[0].asInt(),
            attestationSecurityLevel = fields[1].asInt(),
            keymasterVersion = fields[2].asInt(),
            keymasterSecurityLevel = fields[3].asInt(),
            challenge = challengeBytes.takeIf { it.size == 32 }?.let { Sha256.fromBytes(it) },
            challengeRaw = challengeBytes,
            rootOfTrust = hardwareEnforced[TAG_ROOT_OF_TRUST]?.let { runCatching { rootOfTrust(it) }.getOrNull() },
            attestedApp = softwareEnforced[TAG_ATTESTATION_APPLICATION_ID]?.let { runCatching { attestedApp(it) }.getOrNull() },
        )
    } catch (_: Exception) {
        null
    }

    /** An AuthorizationList: a SEQUENCE of [tag] EXPLICIT values, keyed by tag number. */
    private fun authorizations(list: Der.Tlv): Map<Int, Der.Tlv> {
        require(list.isSequence) { "AuthorizationList is not a SEQUENCE" }
        return list.children().filter { it.isContextSpecific }.associate { it.number to Der(it.content).read() }
    }

    /** RootOfTrust ::= SEQUENCE { verifiedBootKey, deviceLocked BOOLEAN, verifiedBootState ENUMERATED, ... } */
    private fun rootOfTrust(value: Der.Tlv): RootOfTrust {
        require(value.isSequence) { "RootOfTrust is not a SEQUENCE" }
        val fields = value.children()
        require(fields.size >= 3) { "RootOfTrust too short" }
        val state = fields[2].asInt()
        return RootOfTrust(
            deviceLocked = fields[1].asBoolean(),
            verifiedBootState = VerifiedBootState.entries.getOrNull(state.toInt())
                ?: throw IllegalArgumentException("Unknown verified boot state $state"),
        )
    }

    /**
     * An OCTET STRING holding AttestationApplicationId ::= SEQUENCE {
     *   packageInfos SET OF SEQUENCE { packageName OCTET STRING, version INTEGER },
     *   signatureDigests SET OF OCTET STRING }
     */
    private fun attestedApp(value: Der.Tlv): AttestedApp {
        require(value.isOctetString) { "AttestationApplicationId is not an OCTET STRING" }
        val seq = Der(value.content).read()
        require(seq.isSequence) { "AttestationApplicationId is not a SEQUENCE" }
        val (packages, digests) = seq.children().also { require(it.size == 2) { "AttestationApplicationId needs two sets" } }
        require(packages.tag == Der.TAG_SET && digests.tag == Der.TAG_SET) { "AttestationApplicationId fields are not SETs" }
        return AttestedApp(
            packageNames = packages.children().map { info ->
                val name = info.children().first()
                require(name.isOctetString) { "package name is not an OCTET STRING" }
                name.content.toString(Charsets.UTF_8)
            },
            signerDigests = digests.children().map { digest ->
                require(digest.isOctetString && digest.content.size == 32) { "signature digest is not a SHA-256" }
                Sha256.fromBytes(digest.content)
            },
        )
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

        /**
         * The VeriSpec builds whose bundles are accepted. Every bundle so far was
         * signed by a debug build carrying the debug key of the machine that built
         * it; a release key, or another developer's debug key, is added here or
         * per run (the CLI's `--trusted-signer`, the app's own signer on device).
         */
        val VERISPEC_APPS = TrustedApps(
            packageNames = setOf("ai.verispec.app", "ai.verispec.app.debug"),
            signerDigests = setOf(
                // Debug key of the build machine, CN=Android Debug; signs every bundle to 2026-11-19.
                Sha256("0ea6f674bf933869fdec0a9aff9f8de191cb870dd4ab9e51ab335797b601a304"),
            ),
        )

        const val ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"
        const val TAG_ROOT_OF_TRUST = 704
        const val TAG_ATTESTATION_APPLICATION_ID = 709
        const val SECURITY_LEVEL_SOFTWARE = 0L
        const val SECURITY_LEVEL_TEE = 1L
        const val SECURITY_LEVEL_STRONGBOX = 2L
    }
}
