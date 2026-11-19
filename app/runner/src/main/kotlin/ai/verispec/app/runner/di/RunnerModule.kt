package ai.verispec.app.runner.di

import ai.verispec.core.clauses.YamlClauseCatalog
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.model.TrustedApps
import ai.verispec.core.domain.port.AttestationVerifier
import ai.verispec.core.domain.port.Canonicalizer
import ai.verispec.core.domain.port.ClauseCatalog
import ai.verispec.core.domain.port.Clock
import ai.verispec.core.domain.port.DeviceConditionSource
import ai.verispec.core.domain.port.HardwareTelemetrySource
import ai.verispec.core.domain.port.DeviceProfiler
import ai.verispec.core.domain.port.EvidenceRepository
import ai.verispec.core.domain.port.EvidenceSigner
import ai.verispec.core.domain.port.Hasher
import ai.verispec.core.domain.port.InferenceBackend
import ai.verispec.core.domain.port.MemoryTelemetrySource
import ai.verispec.core.domain.port.PowerTelemetrySource
import ai.verispec.core.domain.port.SignatureVerifier
import ai.verispec.core.domain.port.ThermalTelemetrySource
import ai.verispec.core.domain.usecase.AssembleEvidenceUseCase
import ai.verispec.core.domain.usecase.AwaitPreconditionsUseCase
import ai.verispec.core.domain.usecase.ComputeQuantizationDeltaUseCase
import ai.verispec.core.domain.usecase.ComputeThermalEnvelopeUseCase
import ai.verispec.core.domain.usecase.ExecuteRunUseCase
import ai.verispec.core.domain.usecase.ExportBundleUseCase
import ai.verispec.core.domain.usecase.ManifestIdentity
import ai.verispec.core.domain.usecase.MapClausesUseCase
import ai.verispec.core.domain.usecase.ProfileDeviceUseCase
import ai.verispec.core.domain.usecase.SignEvidenceUseCase
import ai.verispec.core.domain.usecase.ValidateManifestUseCase
import ai.verispec.core.domain.usecase.VerifyBundleUseCase
import ai.verispec.data.runtime.api.BackendRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Composition of the domain use cases. The domain has no DI annotations, so this
 * is the one place that knows how to build it. Backends arrive through Dagger
 * multibindings: every adapter module contributes itself into the map.
 */
@Module
@InstallIn(SingletonComponent::class)
object RunnerModule {

    @Provides @Singleton
    fun backendRegistry(backends: Map<RuntimeId, @JvmSuppressWildcards InferenceBackend>): BackendRegistry =
        BackendRegistry(backends)

    @Provides @Singleton
    fun clauseCatalog(): ClauseCatalog = YamlClauseCatalog()

    @Provides @Singleton
    fun manifestIdentity(canonicalizer: Canonicalizer, hasher: Hasher) = ManifestIdentity(canonicalizer, hasher)

    @Provides @Singleton
    fun validateManifest(canonicalizer: Canonicalizer, hasher: Hasher) = ValidateManifestUseCase(canonicalizer, hasher)

    @Provides @Singleton
    fun executeRun(
        backends: Map<RuntimeId, @JvmSuppressWildcards InferenceBackend>,
        thermal: ThermalTelemetrySource,
        power: PowerTelemetrySource,
        memory: MemoryTelemetrySource,
        clock: Clock,
        hardware: HardwareTelemetrySource,
    ) = ExecuteRunUseCase(backends, thermal, power, memory, clock, hardware)

    @Provides @Singleton
    fun awaitPreconditions(conditions: DeviceConditionSource, clock: Clock) = AwaitPreconditionsUseCase(conditions, clock)

    @Provides @Singleton
    fun profileDevice(profiler: DeviceProfiler) = ProfileDeviceUseCase(profiler)

    @Provides @Singleton
    fun assembleEvidence(canonicalizer: Canonicalizer, hasher: Hasher, clock: Clock) =
        AssembleEvidenceUseCase(canonicalizer, hasher, clock)

    @Provides @Singleton
    fun signEvidence(signer: EvidenceSigner) = SignEvidenceUseCase(signer)

    @Provides @Singleton
    fun mapClauses(catalog: ClauseCatalog) = MapClausesUseCase(catalog)

    @Provides @Singleton
    fun thermalEnvelope() = ComputeThermalEnvelopeUseCase()

    @Provides @Singleton
    fun quantizationDelta() = ComputeQuantizationDeltaUseCase()

    @Provides @Singleton
    fun exportBundle(repository: EvidenceRepository) = ExportBundleUseCase(repository)

    @Provides @Singleton
    fun verifyBundle(
        canonicalizer: Canonicalizer,
        hasher: Hasher,
        signatures: SignatureVerifier,
        attestation: AttestationVerifier,
        trustedApps: TrustedApps,
    ) = VerifyBundleUseCase(canonicalizer, hasher, signatures, attestation, trustedApps)
}
