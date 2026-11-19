package ai.verispec.data.attestation.di

import ai.verispec.core.domain.model.TrustedApps
import ai.verispec.core.domain.port.AttestationVerifier
import ai.verispec.core.domain.port.Canonicalizer
import ai.verispec.core.domain.port.Clock
import ai.verispec.core.domain.port.DeviceProfiler
import ai.verispec.core.domain.port.EvidenceSigner
import ai.verispec.core.domain.port.Hasher
import ai.verispec.core.domain.port.SignatureVerifier
import ai.verispec.core.evidence.EvidenceCanonicalizer
import ai.verispec.core.evidence.Hashing
import ai.verispec.core.evidence.verify.AttestationChainVerifier
import ai.verispec.core.evidence.verify.JcaSignatureVerifier
import ai.verispec.data.attestation.AndroidClock
import ai.verispec.data.attestation.AndroidDeviceProfiler
import ai.verispec.data.attestation.KeystoreEvidenceSigner
import ai.verispec.data.attestation.OwnSigner
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AttestationModule {
    @Binds abstract fun signer(impl: KeystoreEvidenceSigner): EvidenceSigner
    @Binds abstract fun profiler(impl: AndroidDeviceProfiler): DeviceProfiler
    @Binds abstract fun clock(impl: AndroidClock): Clock

    companion object {
        @Provides @Singleton fun canonicalizer(): Canonicalizer = EvidenceCanonicalizer
        @Provides @Singleton fun hasher(): Hasher = Hashing
        @Provides @Singleton fun signatureVerifier(): SignatureVerifier = JcaSignatureVerifier()

        /**
         * Chains must end at one of Google's published hardware attestation
         * roots, so "chain valid" means genuine hardware, not just a chain that
         * is consistent with itself (see README, Verification).
         */
        @Provides @Singleton fun attestationVerifier(): AttestationVerifier =
            AttestationChainVerifier(trustedRootSpkiSha256 = AttestationChainVerifier.GOOGLE_ROOT_SPKI_SHA256)

        /** The pinned VeriSpec builds, plus whichever key signed this one. */
        @Provides @Singleton fun trustedApps(@ApplicationContext context: Context): TrustedApps =
            AttestationChainVerifier.VERISPEC_APPS + OwnSigner.of(context)
    }
}
