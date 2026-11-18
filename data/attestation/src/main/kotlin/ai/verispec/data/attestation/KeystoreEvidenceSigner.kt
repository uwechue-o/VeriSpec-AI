package ai.verispec.data.attestation

import ai.verispec.core.domain.model.DetachedSignature
import ai.verispec.core.domain.model.EncodedCertificate
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.port.Clock
import ai.verispec.core.domain.port.EvidenceSigner
import ai.verispec.core.evidence.Hashing
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware-backed signing with Android Key Attestation.
 *
 * One EC P-256 key per run, generated with `setAttestationChallenge(manifestHash)`
 * so the certificate chain proves both that the key is hardware-backed and which
 * run it was created for. StrongBox is attempted first; when unavailable the
 * downgrade is recorded in the signature (strongBox=false), never hidden.
 */
@Singleton
class KeystoreEvidenceSigner @Inject constructor(
    private val clock: Clock,
) : EvidenceSigner {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    override suspend fun sign(canonicalBytes: ByteArray, challenge: Sha256): DetachedSignature = withContext(Dispatchers.IO) {
        val alias = aliasFor(challenge)
        val strongBox = ensureKey(alias, challenge)
        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        val leaf = keyStore.getCertificateChain(alias).first()

        val sig = Signature.getInstance(ALGORITHM).apply {
            initSign(privateKey)
            update(canonicalBytes)
        }.sign()

        DetachedSignature(
            algorithm = ALGORITHM,
            signature = sig,
            keyId = Hashing.sha256(leaf.publicKey.encoded),
            signedAtEpochMillis = clock.epochMillis(),
            hardwareBacked = isHardwareBacked(privateKey),
            strongBox = strongBox,
            attestationChallenge = challenge,
        )
    }

    override suspend fun attestationChain(challenge: Sha256): List<EncodedCertificate> = withContext(Dispatchers.IO) {
        val alias = aliasFor(challenge)
        ensureKey(alias, challenge)
        keyStore.getCertificateChain(alias).map { EncodedCertificate(it.encoded) }
    }

    /** Returns true when the key lives in StrongBox. Idempotent per alias. */
    private fun ensureKey(alias: String, challenge: Sha256): Boolean {
        if (keyStore.containsAlias(alias)) return isStrongBox(keyStore.getKey(alias, null) as PrivateKey)
        return try {
            generate(alias, challenge, strongBox = true)
            true
        } catch (_: StrongBoxUnavailableException) {
            generate(alias, challenge, strongBox = false)
            false
        } catch (e: Exception) {
            // Some devices throw a generic ProviderException for StrongBox; fall back once.
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            generate(alias, challenge, strongBox = false)
            false
        }
    }

    private fun generate(alias: String, challenge: Sha256, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun keyInfo(key: PrivateKey): KeyInfo? = runCatching {
        KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java)
    }.getOrNull()

    private fun isHardwareBacked(key: PrivateKey): Boolean {
        val info = keyInfo(key) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel >= KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
        } else {
            @Suppress("DEPRECATION")
            info.isInsideSecureHardware
        }
    }

    private fun isStrongBox(key: PrivateKey): Boolean {
        val info = keyInfo(key) ?: return false
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALGORITHM = "SHA256withECDSA"
        fun aliasFor(challenge: Sha256) = "verispec-run-${challenge.hex.take(16)}"
    }
}
