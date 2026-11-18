package ai.verispec.core.evidence.verify

import ai.verispec.core.domain.model.DetachedSignature
import ai.verispec.core.domain.model.EncodedCertificate
import ai.verispec.core.domain.port.SignatureVerifier
import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Verifies a detached signature with plain JCA. Runs identically on Android and
 * on a desktop JVM, which is the point: a third party needs no VeriSpec device.
 */
class JcaSignatureVerifier : SignatureVerifier {

    override fun verify(canonicalBytes: ByteArray, signature: DetachedSignature, leaf: EncodedCertificate): Boolean {
        val cert = parse(leaf)
        require(signature.algorithm in ALLOWED_ALGORITHMS) { "Signature algorithm not allowed: ${signature.algorithm}" }
        val verifier = Signature.getInstance(signature.algorithm)
        verifier.initVerify(cert.publicKey)
        verifier.update(canonicalBytes)
        return verifier.verify(signature.signature)
    }

    companion object {
        val ALLOWED_ALGORITHMS = setOf("SHA256withECDSA", "SHA384withECDSA", "SHA512withECDSA", "SHA256withRSA", "Ed25519")

        fun parse(cert: EncodedCertificate): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cert.der)) as X509Certificate
    }
}
