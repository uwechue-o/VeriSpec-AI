package ai.verispec.tools.cli

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.Security
import java.util.Date

/**
 * Test-only self-signed X.509 leaf so signature verification runs against a real
 * certificate. It deliberately carries no Android attestation extension, which
 * is what makes the "chain invalid, signature valid" case testable.
 */
object SelfSignedCertificates {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun create(keyPair: KeyPair, subject: String = "CN=verispec-test"): ByteArray {
        val now = System.currentTimeMillis()
        val name = X500Name(subject)
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 60_000),
            Date(now + 365L * 24 * 60 * 60 * 1000),
            name,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
            .encoded
    }
}
