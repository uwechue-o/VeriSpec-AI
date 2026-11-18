package ai.verispec.core.evidence.wire

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire model for the evidence bundle. These DTOs are the ONLY thing that touches
 * kotlinx.serialization; the domain stays annotation-free. Field names here are
 * the published JSON Schema field names — renaming one is a schema change.
 */

@Serializable
data class QuantizationDto(
    val scheme: String,
    val bitsPerWeight: Double?,
    val isFloatingPoint: Boolean,
)

@Serializable
data class ModelRefDto(
    val format: String,
    val sha256: String,
    val parameterCount: Long?,
    val quantization: QuantizationDto,
    val sourceUri: String?,
)

@Serializable
data class BackendRefDto(
    val runtime: String,
    val runtimeVersion: String,
    val delegate: String,
    val threadCount: Int,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val accelerator: String? = null,
)

@Serializable
sealed interface WorkloadDto {
    @Serializable
    @SerialName("text_generation")
    data class TextGeneration(
        val prompts: List<String>,
        val maxNewTokens: Int,
        val temperature: Double,
        val topK: Int,
    ) : WorkloadDto

    @Serializable
    @SerialName("tensor_inference")
    data class TensorInference(
        val inputShape: List<Int>,
        val inputMin: Double,
        val inputMax: Double,
    ) : WorkloadDto
}

@Serializable
data class RunScheduleDto(
    val iterations: Int,
    val warmupIterations: Int,
    val interIterationDelayMillis: Long,
    val sustainedDurationMillis: Long?,
)

/**
 * Precondition gate policy (schema 1.1.0). Absent from manifests without a gate:
 * the field is never written as null, so pre-1.1 manifests keep their bytes and ids.
 */
@Serializable
data class PreconditionPolicyDto(
    val requiredThermalStatus: String,
    val minHeadroomMargin: Float?,
    val requireUnplugged: Boolean,
    val minBatteryPercent: Int,
    val screenPolicy: String,
    val stabilizationWindowMillis: Long,
    val maxWaitMillis: Long,
    val proceedIfUnmet: Boolean,
)

@Serializable
data class DeviceConditionsDto(
    val thermalStatus: String,
    val thermalHeadroom: Float?,
    val batteryPercent: Int?,
    val plugged: Boolean,
    val screenOn: Boolean,
    val batteryTemperatureDeciC: Int?,
)

/** What the gate found (schema 1.1.0). Absent, never null, when the manifest had no gate. */
@Serializable
data class PreconditionOutcomeDto(
    val satisfied: Boolean,
    val waitedMillis: Long,
    val unmetAtStart: List<String>,
    val conditionsAtStart: DeviceConditionsDto,
)

/** Manifest content without its id: this is what gets hashed to produce the id. */
@Serializable
data class ManifestContentDto(
    val model: ModelRefDto,
    val backend: BackendRefDto,
    val workload: WorkloadDto,
    val schedule: RunScheduleDto,
    val seed: Long,
    val toolVersion: String,
    val label: String,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val preconditions: PreconditionPolicyDto? = null,
)

@Serializable
data class ManifestDto(
    val manifestId: String,
    val model: ModelRefDto,
    val backend: BackendRefDto,
    val workload: WorkloadDto,
    val schedule: RunScheduleDto,
    val seed: Long,
    val toolVersion: String,
    val label: String,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val preconditions: PreconditionPolicyDto? = null,
)

@Serializable
data class SocDto(val vendor: String, val model: String, val revision: String?)

@Serializable
data class DeviceProfileDto(
    val fingerprint: String,
    val manufacturer: String,
    val deviceModel: String,
    val soc: SocDto,
    val npuDriverVersion: String?,
    val gpuDriverVersion: String?,
    val gpuRenderer: String?,
    val androidRelease: String,
    val apiLevel: Int,
    /** ISO-8601 date, e.g. 2026-07-05. */
    val securityPatch: String,
    val totalRamBytes: Long,
    val cpuCoreCount: Int,
    val supportedAbis: List<String>,
    val thermalHalVersion: String?,
    val kernelVersion: String?,
)

@Serializable
data class ProvenanceDto(
    val modelSha256: String,
    val modelFormat: String,
    val runtime: String,
    val runtimeVersionDeclared: String,
    val runtimeVersionObserved: String?,
    val delegate: String,
    val delegateActuallyUsed: String,
    val npuDriverVersion: String?,
    val gpuDriverVersion: String?,
    val toolVersion: String,
    val capturedAtEpochMillis: Long,
    val notes: List<String>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val accelerator: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val acceleratorActuallyUsed: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val acceleratorsAvailable: List<AcceleratorDeviceDto>? = null,
)

@Serializable
data class AcceleratorDeviceDto(
    val name: String,
    val type: String,
    val version: String?,
    val featureLevel: Long?,
)

@Serializable
data class ThermalSampleDto(
    val elapsedMillis: Long,
    val status: String,
    val headroom: Float?,
    val zoneTemperaturesCelsius: Map<String, Float>,
)

@Serializable
data class PowerSampleDto(
    val elapsedMillis: Long,
    val currentMicroAmps: Long?,
    val voltageMilliVolts: Int?,
    val batteryLevelPercent: Int?,
    val isCharging: Boolean,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val batteryTemperatureDeciC: Int? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val screenOn: Boolean? = null,
)

@Serializable
data class MemoryEnvelopeDto(
    val peakPssKb: Long,
    val peakNativeHeapKb: Long,
    val peakRssKb: Long?,
    val availableAtStartKb: Long,
    val availableAtEndKb: Long,
)

@Serializable
data class IterationDto(
    val index: Int,
    val elapsedMillis: Long,
    val timeToFirstTokenMicros: Long,
    val totalMicros: Long,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val decodeTokensPerSecond: Double,
    val prefillTokensPerSecond: Double,
    val thermalStatusAtStart: String,
    val thermalStatusAtEnd: String,
    val wasThrottled: Boolean,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val clocks: ClockReadingDto? = null,
)

@Serializable
data class ClockReadingDto(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val cpuKhz: List<Long>? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val gpuKhz: Long? = null,
)

@Serializable
data class TelemetryInventoryDto(
    val readable: List<String>,
    val unavailable: List<String>,
)

@Serializable
data class OutputDigestDto(
    val iterationIndex: Int,
    val sha256: String,
    val logitProjection: List<Float>?,
)

@Serializable
data class ObservationsDto(
    val iterations: List<IterationDto>,
    val thermalTimeline: List<ThermalSampleDto>,
    val powerTimeline: List<PowerSampleDto>,
    val memoryPeaks: MemoryEnvelopeDto,
    val outputDigests: List<OutputDigestDto>,
    val stoppedEarlyReason: String?,
    val runStartedAtEpochMillis: Long,
    val runDurationMillis: Long,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val preconditions: PreconditionOutcomeDto? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val telemetry: TelemetryInventoryDto? = null,
)

@Serializable
data class ClauseCoverageDto(
    val clauseId: String,
    val framework: String,
    val reference: String,
    val title: String,
    val status: String,
    val satisfied: List<String>,
    val missing: List<String>,
    val gapDescription: String?,
)

@Serializable
data class SignatureDto(
    val algorithm: String,
    /** Base64 (RFC 4648, with padding). */
    val signature: String,
    val keyId: String,
    val signedAtEpochMillis: Long,
    val hardwareBacked: Boolean,
    val strongBox: Boolean,
    val attestationChallenge: String?,
)

/** Exactly the content that is hashed to produce bundleId and then signed. */
@Serializable
data class UnsignedBundleDto(
    val schemaVersion: String,
    val manifest: ManifestDto,
    val device: DeviceProfileDto,
    val provenance: ProvenanceDto,
    val observations: ObservationsDto,
    val clauseCoverage: List<ClauseCoverageDto>,
)

@Serializable
data class EvidenceBundleDto(
    /** Constant marker so a reader can recognise the document type. */
    val documentType: String = DOCUMENT_TYPE,
    val bundleId: String,
    val schemaVersion: String,
    val manifest: ManifestDto,
    val device: DeviceProfileDto,
    val provenance: ProvenanceDto,
    val observations: ObservationsDto,
    val clauseCoverage: List<ClauseCoverageDto>,
    val signature: SignatureDto,
    /** Base64 DER certificates, leaf first. */
    val attestationChain: List<String>,
) {
    companion object {
        const val DOCUMENT_TYPE = "ai.verispec.evidence-bundle"
    }
}
