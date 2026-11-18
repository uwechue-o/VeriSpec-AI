package ai.verispec.core.evidence

import ai.verispec.core.domain.model.AcceleratorDevice
import ai.verispec.core.domain.model.AcceleratorType
import ai.verispec.core.domain.model.BackendRef
import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.ClauseCoverage
import ai.verispec.core.domain.model.ClauseId
import ai.verispec.core.domain.model.ClockReading
import ai.verispec.core.domain.model.CoverageStatus
import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.DetachedSignature
import ai.verispec.core.domain.model.DeviceConditions
import ai.verispec.core.domain.model.DeviceProfile
import ai.verispec.core.domain.model.EncodedCertificate
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.EvidenceRequirement
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
import ai.verispec.core.domain.model.TelemetryInventory
import ai.verispec.core.domain.model.ThermalSample
import ai.verispec.core.domain.model.ThermalStatus
import ai.verispec.core.domain.model.UnsignedBundle
import ai.verispec.core.domain.model.Workload
import kotlinx.datetime.LocalDate

/** Deterministic sample data for evidence tests. */
object Fixtures {

    val zeroHash = Sha256("0".repeat(64))

    fun manifest(seed: Long = 42L, iterations: Int = 12): RunManifest {
        val base = RunManifest(
            manifestId = ManifestId(zeroHash.hex),
            model = ModelRef(
                format = ModelFormat.GGUF,
                sha256 = Hashing.sha256("model-$seed"),
                parameterCount = 2_000_000_000L,
                quantization = QuantizationSpec.Q4_K_M,
                sourceUri = "file:///sdcard/models/gemma-2b-q4_k_m.gguf",
            ),
            backend = BackendRef(RuntimeId.LLAMA_CPP, SemVer(0, 3, 1), Delegate.CPU, threadCount = 4),
            workload = Workload.TextGeneration(listOf("Summarise the following text: ...", "Translate to French: ..."), 64),
            schedule = RunSchedule(iterations = iterations, warmupIterations = 2, interIterationDelayMillis = 0),
            seed = seed,
            toolVersion = SemVer(0, 1, 0),
            label = "fixture",
        )
        return base.copy(manifestId = ManifestId(Hashing.sha256(EvidenceCanonicalizer.manifestBytes(base)).hex))
    }

    fun device() = DeviceProfile(
        fingerprint = "google/husky/husky:15/AP3A.241005.015/12366759:user/release-keys",
        manufacturer = "Google",
        deviceModel = "Pixel 8 Pro",
        soc = SocIdentity("Google", "Tensor G3", "r1"),
        npuDriverVersion = null,
        gpuDriverVersion = "OpenGL ES 3.2 v1.r44p1",
        gpuRenderer = "Mali-G715",
        androidRelease = "15",
        apiLevel = 35,
        securityPatch = LocalDate(2025, 8, 5),
        totalRamBytes = 12L * 1024 * 1024 * 1024,
        cpuCoreCount = 9,
        supportedAbis = listOf("arm64-v8a"),
        thermalHalVersion = "android.hardware.thermal@2.0",
        kernelVersion = "6.1.68",
    )

    fun observations(iterations: Int = 12): Observations {
        val records = (0 until iterations).map { i ->
            IterationRecord(
                index = i,
                elapsedMillis = 1_500L * i,
                timeToFirstTokenMicros = 180_000L + i * 1_000,
                totalMicros = 1_400_000L + i * 20_000,
                prefillTokens = 12,
                decodeTokens = 64,
                decodeTokensPerSecond = 52.0 - i * 0.7,
                prefillTokensPerSecond = 66.0,
                thermalStatusAtStart = if (i < 8) ThermalStatus.NONE else ThermalStatus.MODERATE,
                thermalStatusAtEnd = if (i < 7) ThermalStatus.NONE else ThermalStatus.MODERATE,
                wasThrottled = i >= 7,
            )
        }
        return Observations(
            iterations = records,
            thermalTimeline = listOf(
                ThermalSample(0, ThermalStatus.NONE, 0.31f, mapOf("cpu" to 36.5f, "battery" to 30.0f)),
                ThermalSample(9_000, ThermalStatus.LIGHT, 0.62f),
                ThermalSample(10_500, ThermalStatus.MODERATE, 0.88f),
            ),
            powerTimeline = listOf(PowerSample(0, -1_200_000, 3_900, 81, false), PowerSample(12_000, -2_400_000, 3_850, 79, false)),
            memoryPeaks = MemoryEnvelope(1_900_000, 1_650_000, 2_100_000, 6_000_000, 5_400_000),
            outputDigests = records.map { OutputDigest(it.index, Hashing.sha256("out-${it.index}"), FloatArray(8) { f -> f * 0.5f }) },
            stoppedEarlyReason = null,
            runStartedAtEpochMillis = 1_760_000_000_000L,
            runDurationMillis = 18_000L,
        )
    }

    fun provenance(manifest: RunManifest, device: DeviceProfile) = ProvenanceRecord(
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
        notes = listOf("StrongBox unavailable; key generated in TEE"),
        accelerator = manifest.backend.accelerator,
        acceleratorActuallyUsed = manifest.backend.accelerator,
        acceleratorsAvailable = if (manifest.backend.accelerator != null) snapdragon865NnapiDevices else null,
    )

    /** The NNAPI devices the OnePlus 8T (SM8250, Android 14) listed on 2026-11-18. */
    val snapdragon865NnapiDevices = listOf(
        AcceleratorDevice("qti-default", AcceleratorType.ACCELERATOR, "1.3-23:build_kona", 30),
        AcceleratorDevice("qti-dsp", AcceleratorType.ACCELERATOR, "1.3-23:build_kona", 30),
        AcceleratorDevice("qti-gpu", AcceleratorType.GPU, "1.3-23:build_kona", 30),
        AcceleratorDevice("qti-hta", AcceleratorType.ACCELERATOR, "1.3-23:build_kona", 30),
        AcceleratorDevice("nnapi-reference", AcceleratorType.CPU, "R.196abd8-2-3", 1_000_008),
    )

    /** A 1.3.0 manifest: LiteRT on NNAPI with one named device. */
    fun acceleratorManifest(name: String = "qti-hta"): RunManifest {
        val base = manifest().copy(
            model = manifest().model.copy(format = ModelFormat.TFLITE, quantization = QuantizationSpec.INT8),
            backend = BackendRef(RuntimeId.LITERT, SemVer(1, 4, 0), Delegate.NNAPI, threadCount = 4, accelerator = name),
            workload = Workload.TensorInference(listOf(1, 224, 224, 3)),
        )
        return base.copy(manifestId = ManifestId(Hashing.sha256(EvidenceCanonicalizer.manifestBytes(base)).hex))
    }

    fun coverage() = listOf(
        ClauseCoverage(
            clauseId = ClauseId("EU-AIA-ART15-1"),
            framework = RegulatoryFramework.EU_AI_ACT,
            reference = "Art. 15(1)",
            title = "Accuracy, robustness and cybersecurity",
            status = CoverageStatus.PARTIAL,
            satisfied = setOf(EvidenceRequirement.PERFORMANCE_ENVELOPE, EvidenceRequirement.THERMAL_TIMELINE),
            missing = setOf(EvidenceRequirement.NUMERICAL_DIVERGENCE),
            gapDescription = "Not evidenced: numerical divergence.",
        ),
    )

    /** A manifest with the default precondition gate (schema 1.1.0). */
    fun gatedManifest(): RunManifest {
        val base = manifest().copy(preconditions = PreconditionPolicy.DEFAULT)
        return base.copy(manifestId = ManifestId(Hashing.sha256(EvidenceCanonicalizer.manifestBytes(base)).hex))
    }

    fun gateOutcome(satisfied: Boolean = true) = PreconditionOutcome(
        satisfied = satisfied,
        waitedMillis = 94_000,
        unmetAtStart = if (satisfied) emptyList() else listOf("Device is charging; unplug it"),
        conditionsAtStart = DeviceConditions(ThermalStatus.NONE, 0.21f, 83, plugged = !satisfied, screenOn = false, batteryTemperatureDeciC = 291),
    )

    /** A 1.2.0 manifest: gated, with the screen held on and dimmed. */
    fun dimmedManifest(): RunManifest {
        val base = manifest().copy(preconditions = PreconditionPolicy(screenPolicy = ScreenPolicy.ON_DIMMED))
        return base.copy(manifestId = ManifestId(Hashing.sha256(EvidenceCanonicalizer.manifestBytes(base)).hex))
    }

    /** The 1.2.0 hardware fields, as a Pixel 6 reports them: clocks readable, thermal zones denied. */
    fun withHardwareTelemetry(o: Observations) = o.copy(
        // Odd iterations miss a core, so the CPU list is absent while the GPU clock is still recorded.
        iterations = o.iterations.map { r ->
            r.copy(clocks = ClockReading(if (r.index % 2 == 0) List(8) { 1_098_000L } else null, 251_000L))
        },
        powerTimeline = o.powerTimeline.map { it.copy(batteryTemperatureDeciC = 304, screenOn = true) },
        telemetry = TelemetryInventory(
            readable = listOf("cpu clock (8 cores)", "gpu clock (/sys/devices/platform/1c500000.mali/cur_freq)", "battery temperature"),
            unavailable = listOf("thermal zones: /sys/class/thermal denied to apps"),
        ),
    )

    fun unsigned(manifest: RunManifest = manifest(), hardware: Boolean = false): UnsignedBundle {
        val device = device()
        return UnsignedBundle(
            schemaVersion = EvidenceBundle.CURRENT_SCHEMA_VERSION,
            manifest = manifest,
            device = device,
            provenance = provenance(manifest, device),
            observations = observations(manifest.schedule.iterations)
                .let { if (manifest.preconditions != null) it.copy(preconditions = gateOutcome()) else it }
                .let { if (hardware) withHardwareTelemetry(it) else it },
            clauseCoverage = coverage(),
        )
    }

    fun bundle(manifest: RunManifest = manifest(), hardware: Boolean = false): EvidenceBundle {
        val u = unsigned(manifest, hardware)
        val bytes = EvidenceCanonicalizer.bundleBytes(u)
        return EvidenceBundle(
            bundleId = BundleId(Hashing.sha256(bytes).hex),
            schemaVersion = u.schemaVersion,
            manifest = u.manifest,
            device = u.device,
            provenance = u.provenance,
            observations = u.observations,
            clauseCoverage = u.clauseCoverage,
            signature = DetachedSignature(
                algorithm = "SHA256withECDSA",
                signature = ByteArray(70) { it.toByte() },
                keyId = Hashing.sha256("key"),
                signedAtEpochMillis = 1_760_000_020_000L,
                hardwareBacked = true,
                strongBox = false,
                attestationChallenge = Sha256(manifest.manifestId.value),
            ),
            attestationChain = listOf(EncodedCertificate(ByteArray(300) { (it * 7).toByte() }), EncodedCertificate(ByteArray(200) { 1 })),
        )
    }
}
