package ai.verispec.tools.analysis

import ai.verispec.core.domain.model.BackendRef
import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.DetachedSignature
import ai.verispec.core.domain.model.DeviceProfile
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.IterationRecord
import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.MemoryEnvelope
import ai.verispec.core.domain.model.ModelFormat
import ai.verispec.core.domain.model.ModelRef
import ai.verispec.core.domain.model.Observations
import ai.verispec.core.domain.model.OutputDigest
import ai.verispec.core.domain.model.PowerSample
import ai.verispec.core.domain.model.ProvenanceRecord
import ai.verispec.core.domain.model.QuantizationSpec
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.RunSchedule
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.model.SemVer
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.model.SocIdentity
import ai.verispec.core.domain.model.ThermalSample
import ai.verispec.core.domain.model.ThermalStatus
import ai.verispec.core.domain.model.Workload
import ai.verispec.core.evidence.EvidenceCanonicalizer
import ai.verispec.core.evidence.Hashing
import kotlinx.datetime.LocalDate

/**
 * Fixtures for the JVM analysis tests: no Android, no device. Bundles built here
 * carry real canonical hashes so the analysis code sees production-shaped data.
 */
object TestBundles {

    const val ITERATIONS = 20

    fun bundle(
        deviceId: String,
        socModel: String,
        seed: Long = 42,
        driftFromIteration: Int? = null,
        driftMagnitude: Float = 0f,
    ): EvidenceBundle {
        val manifest = manifest(seed)
        val device = device(deviceId, socModel)
        val observations = observations(driftFromIteration, driftMagnitude)
        val provenance = ProvenanceRecord(
            modelSha256 = manifest.model.sha256,
            modelFormat = manifest.model.format,
            runtime = manifest.backend.runtime,
            runtimeVersionDeclared = manifest.backend.runtimeVersion,
            runtimeVersionObserved = "b4321",
            delegate = manifest.backend.delegate,
            delegateActuallyUsed = manifest.backend.delegate,
            npuDriverVersion = device.npuDriverVersion,
            gpuDriverVersion = device.gpuDriverVersion,
            toolVersion = manifest.toolVersion,
            capturedAtEpochMillis = 1_760_000_000_500L,
            notes = emptyList(),
        )
        val unsigned = ai.verispec.core.domain.model.UnsignedBundle(
            schemaVersion = EvidenceBundle.CURRENT_SCHEMA_VERSION,
            manifest = manifest,
            device = device,
            provenance = provenance,
            observations = observations,
            clauseCoverage = emptyList(),
        )
        return EvidenceBundle(
            bundleId = BundleId(Hashing.sha256(EvidenceCanonicalizer.bundleBytes(unsigned)).hex),
            schemaVersion = unsigned.schemaVersion,
            manifest = manifest,
            device = device,
            provenance = provenance,
            observations = observations,
            clauseCoverage = emptyList(),
            signature = DetachedSignature(
                algorithm = "SHA256withECDSA",
                signature = ByteArray(70) { it.toByte() },
                keyId = Hashing.sha256("key-$deviceId"),
                signedAtEpochMillis = 1_760_000_020_000L,
                hardwareBacked = true,
                strongBox = false,
                attestationChallenge = Sha256(manifest.manifestId.value),
            ),
            attestationChain = emptyList(),
        )
    }

    fun manifest(seed: Long): RunManifest {
        val draft = RunManifest(
            manifestId = ManifestId("0".repeat(64)),
            model = ModelRef(ModelFormat.GGUF, Hashing.sha256("model"), 2_000_000_000L, QuantizationSpec.Q4_K_M, "file:///models/m.gguf"),
            backend = BackendRef(RuntimeId.LLAMA_CPP, SemVer(0, 3, 1), Delegate.CPU, 4),
            workload = Workload.TextGeneration(listOf("Summarise this."), 64),
            schedule = RunSchedule(ITERATIONS, 2),
            seed = seed,
            toolVersion = SemVer(0, 1, 0),
            label = "fleet-fixture",
        )
        return draft.copy(manifestId = ManifestId(Hashing.sha256(EvidenceCanonicalizer.manifestBytes(draft)).hex))
    }

    fun device(id: String, socModel: String) = DeviceProfile(
        fingerprint = "vendor/$id/$id:15/AP3A/12345:user/release-keys",
        manufacturer = "Vendor",
        deviceModel = id,
        soc = SocIdentity("Vendor", socModel, "r1"),
        npuDriverVersion = null,
        gpuDriverVersion = "OpenGL ES 3.2",
        gpuRenderer = "GPU",
        androidRelease = "15",
        apiLevel = 35,
        securityPatch = LocalDate(2025, 8, 5),
        totalRamBytes = 12L * 1024 * 1024 * 1024,
        cpuCoreCount = 8,
        supportedAbis = listOf("arm64-v8a"),
        thermalHalVersion = "android.hardware.thermal@2.0",
        kernelVersion = "6.1.68",
    )

    /** A deliberately decaying run: tokens/s falls, throttling starts partway through, then the device settles. */
    fun observations(driftFromIteration: Int?, driftMagnitude: Float): Observations {
        val records = (0 until ITERATIONS).map { i ->
            val throttled = i >= 6
            IterationRecord(
                index = i,
                elapsedMillis = 1_500L * i,
                timeToFirstTokenMicros = 180_000L + i * 1_500,
                totalMicros = 1_400_000L + i * 30_000,
                prefillTokens = 12,
                decodeTokens = 64,
                decodeTokensPerSecond = 52.0 - minOf(i, 8) * 0.8 + (i % 2) * 0.1,
                prefillTokensPerSecond = 66.0,
                thermalStatusAtStart = if (throttled) ThermalStatus.MODERATE else ThermalStatus.NONE,
                thermalStatusAtEnd = if (throttled) ThermalStatus.MODERATE else ThermalStatus.NONE,
                wasThrottled = throttled,
            )
        }
        val digests = records.map { r ->
            val drifted = driftFromIteration != null && r.index >= driftFromIteration
            OutputDigest(
                iterationIndex = r.index,
                sha256 = if (drifted) Hashing.sha256("drifted-${r.index}") else Hashing.sha256("out-${r.index}"),
                logitProjection = FloatArray(16) { f -> f * 0.25f + r.index * 0.01f + if (drifted) driftMagnitude else 0f },
            )
        }
        return Observations(
            iterations = records,
            thermalTimeline = listOf(
                ThermalSample(0, ThermalStatus.NONE, 0.31f),
                ThermalSample(18_000, ThermalStatus.MODERATE, 0.91f),
            ),
            powerTimeline = listOf(PowerSample(0, -1_200_000, 3_900, 81, false), PowerSample(28_000, -2_400_000, 3_850, 74, false)),
            memoryPeaks = MemoryEnvelope(1_900_000, 1_650_000, 2_100_000, 6_000_000, 5_400_000),
            outputDigests = digests,
            stoppedEarlyReason = null,
            runStartedAtEpochMillis = 1_760_000_000_000L,
            runDurationMillis = 30_000L,
        )
    }
}
