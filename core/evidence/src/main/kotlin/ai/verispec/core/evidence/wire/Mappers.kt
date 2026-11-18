package ai.verispec.core.evidence.wire

import ai.verispec.core.domain.model.BackendRef
import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.ClauseCoverage
import ai.verispec.core.domain.model.ClauseId
import ai.verispec.core.domain.model.CoverageStatus
import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.DetachedSignature
import ai.verispec.core.domain.model.DeviceConditions
import ai.verispec.core.domain.model.DeviceProfile
import ai.verispec.core.domain.model.EncodedCertificate
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.EvidenceRequirement
import ai.verispec.core.domain.model.ClockReading
import ai.verispec.core.domain.model.IterationRecord
import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.MemoryEnvelope
import ai.verispec.core.domain.model.ModelFormat
import ai.verispec.core.domain.model.ModelRef
import ai.verispec.core.domain.model.Observations
import ai.verispec.core.domain.model.OutputDigest
import ai.verispec.core.domain.model.PowerSample
import ai.verispec.core.domain.model.PreconditionOutcome
import ai.verispec.core.domain.model.PreconditionPolicy
import ai.verispec.core.domain.model.ProvenanceRecord
import ai.verispec.core.domain.model.QuantizationSpec
import ai.verispec.core.domain.model.RegulatoryFramework
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.RunSchedule
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.model.ScreenPolicy
import ai.verispec.core.domain.model.SemVer
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.model.SocIdentity
import ai.verispec.core.domain.model.ThermalSample
import ai.verispec.core.domain.model.TelemetryInventory
import ai.verispec.core.domain.model.ThermalStatus
import ai.verispec.core.domain.model.UnsignedBundle
import ai.verispec.core.domain.model.Workload
import kotlinx.datetime.LocalDate
import java.util.Base64

/* ---------------------------------------------------------------- domain -> wire */

fun QuantizationSpec.toDto() = QuantizationDto(scheme, bitsPerWeight, isFloatingPoint)

fun ModelRef.toDto() = ModelRefDto(format.name, sha256.hex, parameterCount, quantization.toDto(), sourceUri)

fun BackendRef.toDto() = BackendRefDto(runtime.name, runtimeVersion.toString(), delegate.name, threadCount)

fun Workload.toDto(): WorkloadDto = when (this) {
    is Workload.TextGeneration -> WorkloadDto.TextGeneration(prompts, maxNewTokens, temperature, topK)
    is Workload.TensorInference -> WorkloadDto.TensorInference(inputShape, inputMin, inputMax)
}

fun RunSchedule.toDto() = RunScheduleDto(iterations, warmupIterations, interIterationDelayMillis, sustainedDurationMillis)

fun PreconditionPolicy.toDto() = PreconditionPolicyDto(
    requiredThermalStatus = requiredThermalStatus.name,
    minHeadroomMargin = minHeadroomMargin,
    requireUnplugged = requireUnplugged,
    minBatteryPercent = minBatteryPercent,
    screenPolicy = screenPolicy.name,
    stabilizationWindowMillis = stabilizationWindowMillis,
    maxWaitMillis = maxWaitMillis,
    proceedIfUnmet = proceedIfUnmet,
)

fun DeviceConditions.toDto() = DeviceConditionsDto(
    thermalStatus.name, thermalHeadroom, batteryPercent, plugged, screenOn, batteryTemperatureDeciC,
)

fun PreconditionOutcome.toDto() = PreconditionOutcomeDto(satisfied, waitedMillis, unmetAtStart, conditionsAtStart.toDto())

fun RunManifest.toContentDto() = ManifestContentDto(
    model = model.toDto(),
    backend = backend.toDto(),
    workload = workload.toDto(),
    schedule = schedule.toDto(),
    seed = seed,
    toolVersion = toolVersion.toString(),
    label = label,
    preconditions = preconditions?.toDto(),
)

fun RunManifest.toDto() = ManifestDto(
    manifestId = manifestId.value,
    model = model.toDto(),
    backend = backend.toDto(),
    workload = workload.toDto(),
    schedule = schedule.toDto(),
    seed = seed,
    toolVersion = toolVersion.toString(),
    label = label,
    preconditions = preconditions?.toDto(),
)

fun DeviceProfile.toDto() = DeviceProfileDto(
    fingerprint = fingerprint,
    manufacturer = manufacturer,
    deviceModel = deviceModel,
    soc = SocDto(soc.vendor, soc.model, soc.revision),
    npuDriverVersion = npuDriverVersion,
    gpuDriverVersion = gpuDriverVersion,
    gpuRenderer = gpuRenderer,
    androidRelease = androidRelease,
    apiLevel = apiLevel,
    securityPatch = securityPatch.toString(),
    totalRamBytes = totalRamBytes,
    cpuCoreCount = cpuCoreCount,
    supportedAbis = supportedAbis,
    thermalHalVersion = thermalHalVersion,
    kernelVersion = kernelVersion,
)

fun ProvenanceRecord.toDto() = ProvenanceDto(
    modelSha256 = modelSha256.hex,
    modelFormat = modelFormat.name,
    runtime = runtime.name,
    runtimeVersionDeclared = runtimeVersionDeclared.toString(),
    runtimeVersionObserved = runtimeVersionObserved,
    delegate = delegate.name,
    delegateActuallyUsed = delegateActuallyUsed.name,
    npuDriverVersion = npuDriverVersion,
    gpuDriverVersion = gpuDriverVersion,
    toolVersion = toolVersion.toString(),
    capturedAtEpochMillis = capturedAtEpochMillis,
    notes = notes,
)

fun ThermalSample.toDto() = ThermalSampleDto(elapsedMillis, status.name, headroom, zoneTemperaturesCelsius)

fun PowerSample.toDto() = PowerSampleDto(
    elapsedMillis, currentMicroAmps, voltageMilliVolts, batteryLevelPercent, isCharging, batteryTemperatureDeciC, screenOn,
)

fun MemoryEnvelope.toDto() = MemoryEnvelopeDto(peakPssKb, peakNativeHeapKb, peakRssKb, availableAtStartKb, availableAtEndKb)

fun IterationRecord.toDto() = IterationDto(
    index = index,
    elapsedMillis = elapsedMillis,
    timeToFirstTokenMicros = timeToFirstTokenMicros,
    totalMicros = totalMicros,
    prefillTokens = prefillTokens,
    decodeTokens = decodeTokens,
    decodeTokensPerSecond = decodeTokensPerSecond,
    prefillTokensPerSecond = prefillTokensPerSecond,
    thermalStatusAtStart = thermalStatusAtStart.name,
    thermalStatusAtEnd = thermalStatusAtEnd.name,
    wasThrottled = wasThrottled,
    clocks = clocks?.let { ClockReadingDto(it.cpuKhz, it.gpuKhz) },
)

fun OutputDigest.toDto() = OutputDigestDto(iterationIndex, sha256.hex, logitProjection?.toList())

fun Observations.toDto() = ObservationsDto(
    iterations = iterations.map { it.toDto() },
    thermalTimeline = thermalTimeline.map { it.toDto() },
    powerTimeline = powerTimeline.map { it.toDto() },
    memoryPeaks = memoryPeaks.toDto(),
    outputDigests = outputDigests.map { it.toDto() },
    stoppedEarlyReason = stoppedEarlyReason,
    runStartedAtEpochMillis = runStartedAtEpochMillis,
    runDurationMillis = runDurationMillis,
    preconditions = preconditions?.toDto(),
    telemetry = telemetry?.let { TelemetryInventoryDto(it.readable, it.unavailable) },
)

fun ClauseCoverage.toDto() = ClauseCoverageDto(
    clauseId = clauseId.value,
    framework = framework.name,
    reference = reference,
    title = title,
    status = status.name,
    satisfied = satisfied.map { it.name }.sorted(),
    missing = missing.map { it.name }.sorted(),
    gapDescription = gapDescription,
)

fun DetachedSignature.toDto() = SignatureDto(
    algorithm = algorithm,
    signature = Base64.getEncoder().encodeToString(signature),
    keyId = keyId.hex,
    signedAtEpochMillis = signedAtEpochMillis,
    hardwareBacked = hardwareBacked,
    strongBox = strongBox,
    attestationChallenge = attestationChallenge?.hex,
)

fun UnsignedBundle.toDto() = UnsignedBundleDto(
    schemaVersion = schemaVersion.toString(),
    manifest = manifest.toDto(),
    device = device.toDto(),
    provenance = provenance.toDto(),
    observations = observations.toDto(),
    clauseCoverage = clauseCoverage.map { it.toDto() },
)

fun EvidenceBundle.toDto() = EvidenceBundleDto(
    bundleId = bundleId.value,
    schemaVersion = schemaVersion.toString(),
    manifest = manifest.toDto(),
    device = device.toDto(),
    provenance = provenance.toDto(),
    observations = observations.toDto(),
    clauseCoverage = clauseCoverage.map { it.toDto() },
    signature = signature.toDto(),
    attestationChain = attestationChain.map { Base64.getEncoder().encodeToString(it.der) },
)

/* ---------------------------------------------------------------- wire -> domain */

fun QuantizationDto.toDomain() = QuantizationSpec(scheme, bitsPerWeight, isFloatingPoint)

fun ModelRefDto.toDomain() = ModelRef(
    format = ModelFormat.valueOf(format),
    sha256 = Sha256(sha256),
    parameterCount = parameterCount,
    quantization = quantization.toDomain(),
    sourceUri = sourceUri,
)

fun BackendRefDto.toDomain() = BackendRef(
    runtime = RuntimeId.valueOf(runtime),
    runtimeVersion = SemVer.parse(runtimeVersion),
    delegate = Delegate.valueOf(delegate),
    threadCount = threadCount,
)

fun WorkloadDto.toDomain(): Workload = when (this) {
    is WorkloadDto.TextGeneration -> Workload.TextGeneration(prompts, maxNewTokens, temperature, topK)
    is WorkloadDto.TensorInference -> Workload.TensorInference(inputShape, inputMin, inputMax)
}

fun RunScheduleDto.toDomain() = RunSchedule(iterations, warmupIterations, interIterationDelayMillis, sustainedDurationMillis)

fun PreconditionPolicyDto.toDomain() = PreconditionPolicy(
    requiredThermalStatus = ThermalStatus.valueOf(requiredThermalStatus),
    minHeadroomMargin = minHeadroomMargin,
    requireUnplugged = requireUnplugged,
    minBatteryPercent = minBatteryPercent,
    screenPolicy = ScreenPolicy.valueOf(screenPolicy),
    stabilizationWindowMillis = stabilizationWindowMillis,
    maxWaitMillis = maxWaitMillis,
    proceedIfUnmet = proceedIfUnmet,
)

fun DeviceConditionsDto.toDomain() = DeviceConditions(
    ThermalStatus.valueOf(thermalStatus), thermalHeadroom, batteryPercent, plugged, screenOn, batteryTemperatureDeciC,
)

fun PreconditionOutcomeDto.toDomain() = PreconditionOutcome(satisfied, waitedMillis, unmetAtStart, conditionsAtStart.toDomain())

fun ManifestDto.toDomain() = RunManifest(
    manifestId = ManifestId(manifestId),
    model = model.toDomain(),
    backend = backend.toDomain(),
    workload = workload.toDomain(),
    schedule = schedule.toDomain(),
    seed = seed,
    toolVersion = SemVer.parse(toolVersion),
    label = label,
    preconditions = preconditions?.toDomain(),
)

fun DeviceProfileDto.toDomain() = DeviceProfile(
    fingerprint = fingerprint,
    manufacturer = manufacturer,
    deviceModel = deviceModel,
    soc = SocIdentity(soc.vendor, soc.model, soc.revision),
    npuDriverVersion = npuDriverVersion,
    gpuDriverVersion = gpuDriverVersion,
    gpuRenderer = gpuRenderer,
    androidRelease = androidRelease,
    apiLevel = apiLevel,
    securityPatch = LocalDate.parse(securityPatch),
    totalRamBytes = totalRamBytes,
    cpuCoreCount = cpuCoreCount,
    supportedAbis = supportedAbis,
    thermalHalVersion = thermalHalVersion,
    kernelVersion = kernelVersion,
)

fun ProvenanceDto.toDomain() = ProvenanceRecord(
    modelSha256 = Sha256(modelSha256),
    modelFormat = ModelFormat.valueOf(modelFormat),
    runtime = RuntimeId.valueOf(runtime),
    runtimeVersionDeclared = SemVer.parse(runtimeVersionDeclared),
    runtimeVersionObserved = runtimeVersionObserved,
    delegate = Delegate.valueOf(delegate),
    delegateActuallyUsed = Delegate.valueOf(delegateActuallyUsed),
    npuDriverVersion = npuDriverVersion,
    gpuDriverVersion = gpuDriverVersion,
    toolVersion = SemVer.parse(toolVersion),
    capturedAtEpochMillis = capturedAtEpochMillis,
    notes = notes,
)

fun ThermalSampleDto.toDomain() = ThermalSample(elapsedMillis, ThermalStatus.valueOf(status), headroom, zoneTemperaturesCelsius)

fun PowerSampleDto.toDomain() = PowerSample(
    elapsedMillis, currentMicroAmps, voltageMilliVolts, batteryLevelPercent, isCharging, batteryTemperatureDeciC, screenOn,
)

fun MemoryEnvelopeDto.toDomain() = MemoryEnvelope(peakPssKb, peakNativeHeapKb, peakRssKb, availableAtStartKb, availableAtEndKb)

fun IterationDto.toDomain() = IterationRecord(
    index = index,
    elapsedMillis = elapsedMillis,
    timeToFirstTokenMicros = timeToFirstTokenMicros,
    totalMicros = totalMicros,
    prefillTokens = prefillTokens,
    decodeTokens = decodeTokens,
    decodeTokensPerSecond = decodeTokensPerSecond,
    prefillTokensPerSecond = prefillTokensPerSecond,
    thermalStatusAtStart = ThermalStatus.valueOf(thermalStatusAtStart),
    thermalStatusAtEnd = ThermalStatus.valueOf(thermalStatusAtEnd),
    wasThrottled = wasThrottled,
    clocks = clocks?.let { ClockReading(it.cpuKhz, it.gpuKhz) },
)

fun OutputDigestDto.toDomain() = OutputDigest(iterationIndex, Sha256(sha256), logitProjection?.toFloatArray())

fun ObservationsDto.toDomain() = Observations(
    iterations = iterations.map { it.toDomain() },
    thermalTimeline = thermalTimeline.map { it.toDomain() },
    powerTimeline = powerTimeline.map { it.toDomain() },
    memoryPeaks = memoryPeaks.toDomain(),
    outputDigests = outputDigests.map { it.toDomain() },
    stoppedEarlyReason = stoppedEarlyReason,
    runStartedAtEpochMillis = runStartedAtEpochMillis,
    runDurationMillis = runDurationMillis,
    preconditions = preconditions?.toDomain(),
    telemetry = telemetry?.let { TelemetryInventory(it.readable, it.unavailable) },
)

fun ClauseCoverageDto.toDomain() = ClauseCoverage(
    clauseId = ClauseId(clauseId),
    framework = RegulatoryFramework.valueOf(framework),
    reference = reference,
    title = title,
    status = CoverageStatus.valueOf(status),
    satisfied = satisfied.map { EvidenceRequirement.valueOf(it) }.toSet(),
    missing = missing.map { EvidenceRequirement.valueOf(it) }.toSet(),
    gapDescription = gapDescription,
)

fun SignatureDto.toDomain() = DetachedSignature(
    algorithm = algorithm,
    signature = Base64.getDecoder().decode(signature),
    keyId = Sha256(keyId),
    signedAtEpochMillis = signedAtEpochMillis,
    hardwareBacked = hardwareBacked,
    strongBox = strongBox,
    attestationChallenge = attestationChallenge?.let { Sha256(it) },
)

fun UnsignedBundleDto.toDomain() = UnsignedBundle(
    schemaVersion = SemVer.parse(schemaVersion),
    manifest = manifest.toDomain(),
    device = device.toDomain(),
    provenance = provenance.toDomain(),
    observations = observations.toDomain(),
    clauseCoverage = clauseCoverage.map { it.toDomain() },
)

fun EvidenceBundleDto.toDomain(): EvidenceBundle {
    require(documentType == EvidenceBundleDto.DOCUMENT_TYPE) { "Not an evidence bundle document: $documentType" }
    return EvidenceBundle(
        bundleId = BundleId(bundleId),
        schemaVersion = SemVer.parse(schemaVersion),
        manifest = manifest.toDomain(),
        device = device.toDomain(),
        provenance = provenance.toDomain(),
        observations = observations.toDomain(),
        clauseCoverage = clauseCoverage.map { it.toDomain() },
        signature = signature.toDomain(),
        attestationChain = attestationChain.map { EncodedCertificate(Base64.getDecoder().decode(it)) },
    )
}
