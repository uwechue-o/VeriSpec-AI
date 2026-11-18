
VeriSpec-AI — a conformance evidence toolkit for on-device AI

The thesis: everyone can run a model on a phone. Almost nobody can prove how it behaves once it's out in a fleet. Regulated industries are about to be required to.

The timing is unusually good. The Digital Omnibus on AI deferred high-risk obligations for standalone Annex III systems to December 2, 2027, and for AI embedded in regulated products like medical devices and machinery to August 2, 2028. That's a two-year runway — long enough to build the tooling and be established before demand peaks, short enough that it's not speculative. The Commission's stated reason for the delay was that the conformity-assessment infrastructure the Act assumes would exist had not yet matured. That's a description of a missing product.

What it emits: run a model across a device fleet, get back a signed, reproducible evidence bundle containing —

Provenance: hashes of model artifact, runtime version, delegate, NPU driver/firmware version. Trivial-sounding, currently nobody does it.
Cross-device numerical divergence: identical input and model, N SoCs and delegates, measured output drift. Quantized inference is not bit-identical across vendors and this is barely documented anywhere. This alone is a publishable finding.
Quantization accuracy delta against an FP reference.
Sustained-load performance envelope — ThermalBench becomes one module rather than the whole project.
[ThermalBench is a thermal-aware sustained-load inference benchmark for Android; it is yet to be built]
Clause mapping: each artifact tied to EU AI Act Art. 15 and Annex IV technical documentation, ISO/IEC 42001, ISO/PAS 8800 for automotive AI safety, and FDA PCCP for AI-enabled medical devices.

Output as machine-readable JSON plus a human-readable PDF that goes straight into a submission binder.

===========================

VeriSpec-AI — Software Architecture

A conformance evidence toolkit for on-device AI. Kotlin · Clean Architecture · MVVM · Multi-module Gradle

1. Architectural drivers

Before the layer diagram, the four constraints that shape every decision below. This is not a normal Android app, and the differences matter.

Driver	Consequence
Evidence must be reproducible	Every run is described by an immutable, hashed RunManifest. No hidden state, no wall-clock dependence, no unordered serialization.
Evidence must be tamper-evident	Bundles are signed by a hardware-backed key with Android Key Attestation, so the certificate chain proves the run happened on a real device with a real TEE.
Regulation moves	Clause mappings are data, loaded from versioned YAML resources — never hardcoded. The Digital Omnibus deferral already proved this.
Runtimes are swappable and hostile to abstraction	Each inference backend sits behind a narrow port with an explicit capability model. The domain never learns what a delegate is.


2. Module topology
VeriSpec-AI/
├── core/
│   ├── domain/                 pure Kotlin/JVM. Zero Android, zero framework.
│   ├── evidence/               bundle model, canonical serialization, JSON Schema
│   └── clauses/                regulatory clause catalog + mapper (data-driven)
│
├── data/
│   ├── runtime-api/            InferenceBackend port + capability model
│   ├── runtime-litert/         LiteRT / LiteRT-LM adapter
│   ├── runtime-llamacpp/       llama.cpp JNI adapter (GGUF)
│   ├── runtime-executorch/     ExecuTorch .pte adapter
│   ├── runtime-mediapipe/      MediaPipe Tasks GenAI adapter
│   ├── telemetry/              thermal, power, memory, frequency sources
│   ├── attestation/            Keystore signing, key attestation, provenance
│   └── persistence/            Room DB, DataStore, artifact store
│
├── app/
│   ├── ui/                     Compose + MVVM (the on-device operator console)
│   └── runner/                 WorkManager foreground execution engine
│
├── tools/
│   ├── cli/                    JVM fleet orchestrator over ADB
│   ├── analysis/               divergence + decay analytics (JVM, headless)
│   └── report/                 PDF / JSON bundle rendering
│
└── build-logic/                convention plugins, version catalog

Dependency rule: arrows point inward only. core/domain depends on nothing but the Kotlin stdlib and coroutines. tools/* and app/* may depend on core and data; never the reverse. Enforce this mechanically with a Gradle convention plugin, not with discipline.

Why tools/analysis is JVM-only: divergence analysis compares results across devices, so it cannot run on any one device. This split is load-bearing, not stylistic.

3. Domain layer (core/domain)

Pure Kotlin. No Android, no Hilt, no serialization annotations. This is the part a regulator's technical reviewer could read.

3.1 Core entities
kotlin
/** Immutable, hashable specification of a run. The hash IS the run identity. */
data class RunManifest(
    val manifestId: ManifestId,
    val model: ModelRef,
    val backend: BackendRef,
    val workload: Workload,
    val schedule: RunSchedule,
    val seed: Long,
    val toolVersion: SemVer,
)

data class ModelRef(
    val format: ModelFormat,            // GGUF | LITERT | EXECUTORCH | TFLITE
    val sha256: Sha256,
    val parameterCount: Long?,
    val quantization: QuantizationSpec, // e.g. Q4_K_M, INT8, FP16
    val sourceUri: String?,
)

data class BackendRef(
    val runtime: RuntimeId,             // LITERT_LM | LLAMA_CPP | EXECUTORCH | MEDIAPIPE
    val runtimeVersion: SemVer,
    val delegate: Delegate,             // CPU | GPU | NNAPI | QNN | VENDOR_NPU
    val threadCount: Int,
)

/** Everything observed about the machine, captured at run time, not assumed. */
data class DeviceProfile(
    val fingerprint: String,
    val soc: SocIdentity,               // vendor, model, revision
    val npuDriverVersion: String?,
    val gpuDriverVersion: String?,
    val androidRelease: String,
    val securityPatch: LocalDate,
    val totalRamBytes: Long,
    val thermalHalVersion: String?,
)

/** The output artifact. Signed, canonical, self-describing. */
data class EvidenceBundle(
    val bundleId: BundleId,
    val schemaVersion: SemVer,
    val manifest: RunManifest,
    val device: DeviceProfile,
    val provenance: ProvenanceRecord,
    val observations: Observations,
    val clauseCoverage: List<ClauseCoverage>,
    val signature: DetachedSignature,
    val attestationChain: List<X509Certificate>,
)
3.2 Observation types
kotlin
data class Observations(
    val iterations: List<IterationRecord>,
    val thermalTimeline: List<ThermalSample>,
    val powerTimeline: List<PowerSample>,
    val memoryPeaks: MemoryEnvelope,
    val outputDigests: List<OutputDigest>,   // for cross-device divergence
)

data class IterationRecord(
    val index: Int,
    val timeToFirstTokenMicros: Long,
    val decodeTokensPerSecond: Double,
    val prefillTokensPerSecond: Double,
    val thermalStatusAtStart: ThermalStatus,
    val wasThrottled: Boolean,
)

/**
 * A digest, not raw output. Bundles must not carry model outputs —
 * they may contain input-derived PII. Store the hash plus a fixed-size
 * numeric projection sufficient for divergence math.
 */
data class OutputDigest(
    val iterationIndex: Int,
    val sha256: Sha256,
    val logitProjection: FloatArray?,   // optional, opt-in
)

That PII constraint is easy to miss and expensive to retrofit. Decide it now.

3.3 Ports (repository interfaces)
kotlin
interface InferenceBackend {
    val id: RuntimeId
    suspend fun capabilities(): BackendCapabilities
    suspend fun load(model: ModelRef, delegate: Delegate): LoadedModel
    suspend fun infer(model: LoadedModel, input: WorkloadInput): InferenceResult
    suspend fun unload(model: LoadedModel)
}

interface ThermalTelemetrySource {
    fun stream(): Flow<ThermalSample>
    suspend fun halVersion(): String?
}

interface PowerTelemetrySource { fun stream(): Flow<PowerSample> }

interface DeviceProfiler { suspend fun profile(): DeviceProfile }

interface EvidenceSigner {
    suspend fun sign(canonicalBytes: ByteArray): DetachedSignature
    suspend fun attestationChain(): List<X509Certificate>
}

interface EvidenceRepository {
    suspend fun save(bundle: EvidenceBundle): BundleId
    fun observeAll(): Flow<List<BundleSummary>>
    suspend fun load(id: BundleId): EvidenceBundle?
    suspend fun export(id: BundleId, format: ExportFormat): ExportedArtifact
}

interface ClauseCatalog {
    suspend fun catalog(version: CatalogVersion): List<ClauseDefinition>
}
3.4 Use cases

One class, one operator fun invoke, constructor-injected ports.

kotlin
class ExecuteRunUseCase(
    private val backends: Map<RuntimeId, InferenceBackend>,
    private val thermal: ThermalTelemetrySource,
    private val power: PowerTelemetrySource,
    private val profiler: DeviceProfiler,
    private val clock: Clock,
) {
    operator fun invoke(manifest: RunManifest): Flow<RunProgress>
}

The full set:

Use case	Responsibility
ValidateManifestUseCase	Reject non-reproducible manifests before wasting an hour of device time
ExecuteRunUseCase	Drive N iterations, emit progress, collect observations
ProfileDeviceUseCase	Capture DeviceProfile incl. driver/firmware versions
AssembleEvidenceUseCase	Observations → canonical bundle
SignEvidenceUseCase	Hardware-backed signature + attestation chain
MapClausesUseCase	Bundle × clause catalog → ClauseCoverage with gaps named
ComputeDivergenceUseCase	N bundles → cross-device divergence report (JVM side)
ComputeThermalEnvelopeUseCase	Decay curve, steady-state floor, time-to-throttle
ComputeQuantizationDeltaUseCase	Quantized vs FP reference accuracy delta
ExportBundleUseCase	JSON + PDF rendering
VerifyBundleUseCase	Independent third party verifies a signature — critical for credibility

VerifyBundleUseCase matters more than it looks. If nobody but you can verify a bundle, it isn't evidence.

4. Presentation layer (MVVM + Compose)
4.1 State contract
kotlin
sealed interface RunUiState {
    data object Idle : RunUiState
    data class Configuring(val form: ManifestForm, val errors: List<FieldError>) : RunUiState
    data class Running(
        val progress: Float,
        val currentIteration: Int,
        val totalIterations: Int,
        val liveThermal: ThermalStatus,
        val liveTokensPerSecond: Double,
        val decayChart: List<Point>,
    ) : RunUiState
    data class Complete(val bundleId: BundleId, val summary: BundleSummary) : RunUiState
    data class Failed(val reason: RunFailure) : RunUiState
}

sealed interface RunEffect {
    data class ShowError(val message: UiText) : RunEffect
    data class NavigateToBundle(val id: BundleId) : RunEffect
}
kotlin
@HiltViewModel
class RunViewModel @Inject constructor(
    private val executeRun: ExecuteRunUseCase,
    private val validateManifest: ValidateManifestUseCase,
    private val assembleEvidence: AssembleEvidenceUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow<RunUiState>(RunUiState.Idle)
    val state: StateFlow<RunUiState> = _state.asStateFlow()

    private val _effects = Channel<RunEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun onIntent(intent: RunIntent) { /* ... */ }
}

Rule: ViewModels hold no domain logic and no Android Context. They translate intents to use cases and domain results to UI state. Composables receive state and a (RunIntent) -> Unit — never the ViewModel itself, so previews and screenshot tests work.

4.2 Screens
Screen	Purpose
Run Composer	Build/import a RunManifest, validate before commit
Live Run	Real-time decay chart, thermal state, throttle events
Bundle Detail	Observations, signature status, attestation chain viewer
Clause Coverage	Which clauses this bundle satisfies, which are not covered
Fleet	Bundles imported from other devices; divergence summary
Verify	Paste/import a foreign bundle, verify its signature

The Clause Coverage screen must display gaps as prominently as coverage. A compliance tool that only shows green is a liability.

4.3 Execution engine

Sustained-load runs last tens of minutes and must survive process death, screen-off and Doze.

WorkManager with a long-running foreground worker, one worker per run.
Worker holds only a ManifestId; it re-resolves everything from persistence, so restart is idempotent.
Partial IterationRecords are flushed to Room after each iteration — a crash at iteration 47 must not lose 46 iterations.
Wake locks acquired explicitly and released in finally. Document them; they affect your own power measurements.
5. Data layer notes

Runtime adapters. Each backend module is independently replaceable and independently absent — capability discovery at startup, not compile-time assumption. A device without a QNN delegate should degrade to a recorded gap, not a crash.

Canonical serialization. Two runs producing identical observations must produce byte-identical JSON before signing. That means sorted keys, fixed float formatting, explicit UTC, no default-value omission ambiguity. Write a CanonicalJson encoder and property-test it. This is the single most common place these tools break.

Attestation. Generate the signing key in the Android Keystore with setAttestationChallenge() bound to the manifest hash. The resulting chain proves both that the key is hardware-backed and which run it was created for. On devices without StrongBox, record the downgrade in the bundle rather than hiding it.

Persistence. Room for run history and iteration records (high write volume; use a transaction per iteration batch). DataStore for settings. Bundles themselves are files in app-scoped storage, referenced by row — never blobs in SQLite.

6. Third-party libraries
Core / language
Library	Coordinates	Purpose
Coroutines	org.jetbrains.kotlinx:kotlinx-coroutines-core	Async, Flow for telemetry
kotlinx.serialization	org.jetbrains.kotlinx:kotlinx-serialization-json	Bundle serialization (with custom canonical encoder)
kotlinx-datetime	org.jetbrains.kotlinx:kotlinx-datetime	UTC instants; avoids java.time desugaring pitfalls
kotlinx-io / Okio	com.squareup.okio:okio	Streaming hashes over multi-GB model files without OOM
Arrow Core	io.arrow-kt:arrow-core	Either/Raise for typed domain errors (optional but suits this domain)
DI
Library	Coordinates	Purpose
Hilt	com.google.dagger:hilt-android	Android graph — you already use this
Dagger	com.google.dagger:dagger	Plain Dagger for the CLI/JVM modules (Hilt is Android-only)
KSP	com.google.devtools.ksp	Annotation processing; drop kapt
Android UI
Library	Purpose
androidx.compose:compose-bom + Material3	UI
androidx.navigation:navigation-compose	Navigation
androidx.lifecycle:lifecycle-viewmodel-compose	ViewModel wiring
androidx.lifecycle:lifecycle-runtime-compose	collectAsStateWithLifecycle
Vico com.patrykandpatrick.vico:compose-m3	Live decay/thermal charts in Compose. Lighter than MPAndroidChart and Compose-native
Persistence
Library	Purpose
androidx.room:room-ktx + room-compiler (KSP)	Run history, iteration records
androidx.datastore:datastore-preferences	Settings
androidx.work:work-runtime-ktx	Long-running foreground run execution
Instrumentation & measurement
Library	Purpose
androidx.benchmark:benchmark-macro-junit4	Rigorous timing methodology; borrow its warmup/outlier handling
androidx.tracing:tracing-perfetto	Perfetto trace markers around inference phases
androidx.test.uiautomator	Driving runs from the CLI without a human
Perfetto SDK / trace processor	Post-hoc trace analysis on the JVM side

Platform APIs, not libraries, but central: PowerManager.getCurrentThermalStatus(), addThermalStatusListener(), BatteryManager, HardwarePropertiesManager, Debug.MemoryInfo, ActivityManager.getMemoryInfo().

Inference runtimes
Library	Coordinates	Purpose
LiteRT	com.google.ai.edge.litert:litert	Primary Google runtime
LiteRT-LM	com.google.ai.edge.litert:litert-lm	LLM-specific path (Gemma family)
MediaPipe Tasks GenAI	com.google.mediapipe:tasks-genai	Second Google-stack path
llama.cpp	JNI wrapper, built via CMake/NDK	GGUF — the format Google will never ship. Your differentiator
ExecuTorch	org.pytorch:executorch-android	.pte runtime
ONNX Runtime Mobile	com.microsoft.onnxruntime:onnxruntime-android	Optional fourth backend; strong in automotive
Play AI Delivery	com.google.android.play:ai-delivery	Model delivery for large artifacts
Numerics & statistics (JVM analysis module)
Library	Coordinates	Purpose
Multik	org.jetbrains.kotlinx:multik-core / multik-default	Kotlin ndarray; divergence metrics (cosine, L∞, KL)
Apache Commons Math3	org.apache.commons:commons-math3	Percentiles, regression on decay curves, distribution tests
kotlin-statistics or Kotlin DataFrame	org.jetbrains.kotlinx:dataframe	Fleet-level aggregation, CSV export
Cryptography, signing, provenance
Library	Coordinates	Purpose
Tink	com.google.crypto.tink:tink-android	Signing primitives; safer defaults than raw JCA
Bouncy Castle	org.bouncycastle:bcpkix-jdk18on	X.509 attestation-chain parsing and verification (JVM verifier)
Nimbus JOSE+JWT	com.nimbusds:nimbus-jose-jwt	JWS detached signatures if you choose JOSE over raw
Android Key Attestation	platform + bcpkix for parsing	Hardware-backed proof the run occurred on a genuine device

Consider aligning the bundle format with in-toto/SLSA attestation predicates rather than inventing one. Reviewers recognize it, and it costs you almost nothing at design time.

Reporting
Library	Coordinates	Purpose
Apache PDFBox	org.apache.pdfbox:pdfbox	PDF generation. Apache 2.0
XChart or JFreeChart	org.knowm.xchart:xchart	Server-side chart rendering into the PDF
kotlinx-html	org.jetbrains.kotlinx:kotlinx-html-jvm	Optional HTML intermediate for web-viewable reports
networknt json-schema-validator	com.networknt:json-schema-validator	Validate bundles against the published JSON Schema
kaml	com.charleskorn.kaml:kaml	Parse the versioned clause catalog YAML

Licensing note: avoid iText 7 despite your prior experience with it — it's AGPL, which forces AGPL on downstream users or a commercial license. For a toolkit meant to be adopted by medical-device and automotive companies, that's disqualifying. PDFBox under Apache 2.0 is the right call.

CLI / fleet orchestration
Library	Coordinates	Purpose
Clikt	com.github.ajalt.clikt:clikt	Kotlin CLI framework
adam	com.malinskiy.adam:adam	Kotlin/coroutines ADB client — drive a device fleet without shelling out
Mordant	com.github.ajalt.mordant:mordant	Terminal tables/progress
Ktor Client	io.ktor:ktor-client-cio	Optional leaderboard/bundle upload
Testing
Library	Coordinates	Purpose
JUnit 5	org.junit.jupiter:junit-jupiter	JVM tests
Kotest (property testing)	io.kotest:kotest-property	Property-test canonical serialization determinism — essential here
MockK	io.mockk:mockk	Kotlin-idiomatic mocking (over Mockito for suspend/final classes)
Turbine	app.cash.turbine:turbine	Flow assertions for telemetry streams
Robolectric	org.robolectric:robolectric	Android unit tests
Truth	com.google.truth:truth	Readable assertions
Paparazzi	app.cash.paparazzi	Screenshot tests for Compose without a device
Kover	org.jetbrains.kotlinx:kover	Coverage (Kotlin-native successor to Jacoco)
Build & quality
Tool	Purpose
Gradle Version Catalogs (libs.versions.toml)	Single source of dependency truth
Convention plugins in build-logic	Enforce the dependency rule mechanically
Detekt + Spotless/ktlint	Static analysis, formatting
binary-compatibility-validator	Public API surface is part of the evidence contract
Dokka	API docs
Reproducible builds (isReproducibleFileOrder, deterministic timestamps)	The toolkit that asserts reproducibility must itself build reproducibly
7. Build order

Don't build this breadth-first. Suggested sequence:

core/domain + core/evidence with canonical serialization and property tests. No UI.
One backend (runtime-litert) and telemetry (thermal only).
ExecuteRunUseCase + WorkManager runner + a deliberately ugly Compose screen.
attestation + VerifyBundleUseCase. Now bundles are real evidence.
tools/analysis divergence across two devices. This is your first publishable result — stop and write it up.
tools/report PDF output.
core/clauses + coverage UI.
Additional backends, then tools/cli fleet orchestration.

Steps 1–5 are roughly the ThermalBench scope with the evidence spine already in place. That's the version to ship first and publish from; everything after is expansion once you know people care.

8. Two decisions worth making explicitly

Bundle schema versioning. Publish the JSON Schema as a versioned artifact from day one, separate from the code. Downstream tools will target the schema, not your Kotlin. Breaking it later is far more expensive than designing it carefully now.

Neutrality guardrails. Since the toolkit's value depends on being trusted across runtimes, encode neutrality structurally: identical measurement code paths for every backend, no backend-specific tuning, and a documented policy that any vendor may submit adapter fixes. Say this in the README. It's the thing that makes the numbers count.
