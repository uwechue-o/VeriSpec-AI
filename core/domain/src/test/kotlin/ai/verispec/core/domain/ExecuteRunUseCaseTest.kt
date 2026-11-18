package ai.verispec.core.domain

import ai.verispec.core.domain.model.BackendCapabilities
import ai.verispec.core.domain.model.BackendRef
import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.InferenceResult
import ai.verispec.core.domain.model.LoadedModel
import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.MemorySnapshot
import ai.verispec.core.domain.model.ModelFormat
import ai.verispec.core.domain.model.ModelRef
import ai.verispec.core.domain.model.PowerSample
import ai.verispec.core.domain.model.QuantizationSpec
import ai.verispec.core.domain.model.RunFailure
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.RunProgress
import ai.verispec.core.domain.model.RunSchedule
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.model.SemVer
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.model.ThermalSample
import ai.verispec.core.domain.model.Workload
import ai.verispec.core.domain.model.WorkloadInput
import ai.verispec.core.domain.port.Clock
import ai.verispec.core.domain.port.InferenceBackend
import ai.verispec.core.domain.port.MemoryTelemetrySource
import ai.verispec.core.domain.port.PowerTelemetrySource
import ai.verispec.core.domain.port.ThermalTelemetrySource
import ai.verispec.core.domain.usecase.ExecuteRunUseCase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The measurement loop is where a backend failure must turn into a recorded
 * failure instead of evidence: these tests drive it with a scripted backend.
 */
class ExecuteRunUseCaseTest : StringSpec({

    "a clean run warms up, measures every iteration in order and unloads once" {
        val backend = ScriptedBackend()
        val events = execute(backend, manifest(iterations = 4, warmup = 2))

        events.filterIsInstance<RunProgress.Warmup>().map { it.index } shouldContainExactly listOf(1, 2)
        events.filterIsInstance<RunProgress.Iteration>().map { it.record.index } shouldContainExactly listOf(0, 1, 2, 3)
        val completed = events.last().shouldBeInstanceOf<RunProgress.Completed>()
        completed.observations.iterations.map { it.index } shouldContainExactly listOf(0, 1, 2, 3)
        completed.observations.outputDigests.map { it.sha256 } shouldContainExactly (0..3).map { digestFor(it) }
        completed.observations.stoppedEarlyReason shouldBe null
        // Warm-up uses the inputs of the first iterations; the measured loop starts again from 0.
        backend.calls shouldContainExactly listOf(0, 1, 0, 1, 2, 3)
        backend.unloads shouldBe 1
    }

    "a failure during warm-up fails the run, records nothing and still unloads" {
        val backend = ScriptedBackend(failAt = setOf(0), failMessage = "empty response")
        val events = execute(backend, manifest(iterations = 5, warmup = 3))

        val failed = events.last().shouldBeInstanceOf<RunProgress.Failed>()
        val failure = failed.failure.shouldBeInstanceOf<RunFailure.InferenceFailed>()
        failure.iteration shouldBe 0
        failure.detail shouldBe "during warm-up 1 of 3: empty response"
        events.filterIsInstance<RunProgress.Iteration>().shouldBeEmpty()
        events.filterIsInstance<RunProgress.Completed>().shouldBeEmpty()
        backend.unloads shouldBe 1
    }

    "a failure in a later warm-up names that warm-up" {
        val backend = ScriptedBackend(failAt = setOf(1))
        val events = execute(backend, manifest(iterations = 5, warmup = 3))

        val failure = events.last().shouldBeInstanceOf<RunProgress.Failed>().failure.shouldBeInstanceOf<RunFailure.InferenceFailed>()
        failure.detail shouldBe "during warm-up 2 of 3: boom"
        events.filterIsInstance<RunProgress.Warmup>().map { it.index } shouldContainExactly listOf(1)
    }

    "a failure mid-run stops at that iteration and never produces a bundle" {
        val backend = ScriptedBackend(failAt = setOf(2))
        val events = execute(backend, manifest(iterations = 5, warmup = 0))

        val failure = events.last().shouldBeInstanceOf<RunProgress.Failed>().failure.shouldBeInstanceOf<RunFailure.InferenceFailed>()
        failure.iteration shouldBe 2
        failure.detail shouldBe "boom"
        events.filterIsInstance<RunProgress.Iteration>().map { it.record.index } shouldContainExactly listOf(0, 1)
        events.filterIsInstance<RunProgress.Completed>().shouldBeEmpty()
        backend.unloads shouldBe 1
    }

    "an exception without a message is still named" {
        val backend = ScriptedBackend(failAt = setOf(0), failWith = { IllegalStateException() })
        val events = execute(backend, manifest(iterations = 2, warmup = 0))

        events.last().shouldBeInstanceOf<RunProgress.Failed>().failure.shouldBeInstanceOf<RunFailure.InferenceFailed>()
            .detail shouldBe "IllegalStateException"
    }

    "a model that fails to load is a recorded failure and is never unloaded" {
        val backend = ScriptedBackend(loadFailure = IllegalArgumentException("bad file"))
        val events = execute(backend, manifest())

        events.last().shouldBeInstanceOf<RunProgress.Failed>().failure shouldBe RunFailure.ModelLoadFailed("bad file")
        backend.calls.shouldBeEmpty()
        backend.unloads shouldBe 0
    }

    "a missing adapter, an unavailable runtime, a wrong format or delegate fail before anything loads" {
        val absent = execute(emptyMap(), manifest())
        absent.single().shouldBeInstanceOf<RunProgress.Failed>().failure.shouldBeInstanceOf<RunFailure.BackendUnavailable>()

        val unavailable = ScriptedBackend(caps = BackendCapabilities.unavailable(RuntimeId.MEDIAPIPE, "no native library"))
        execute(unavailable, manifest()).single().shouldBeInstanceOf<RunProgress.Failed>().failure shouldBe
            RunFailure.BackendUnavailable(RuntimeId.MEDIAPIPE, "no native library")

        val wrongFormat = ScriptedBackend()
        execute(wrongFormat, manifest(format = ModelFormat.GGUF)).single().shouldBeInstanceOf<RunProgress.Failed>()
            .failure.shouldBeInstanceOf<RunFailure.UnsupportedFormat>()

        val cpuOnly = ScriptedBackend(caps = capabilities(delegates = setOf(Delegate.CPU)))
        execute(cpuOnly, manifest(delegate = Delegate.GPU)).single().shouldBeInstanceOf<RunProgress.Failed>()
            .failure.shouldBeInstanceOf<RunFailure.DelegateUnavailable>()

        listOf(unavailable, wrongFormat, cpuOnly).forEach { it.loads shouldBe 0 }
    }

    "a duration cap stops the run early and says why" {
        val clock = SteppingClock()
        // Every inference takes one second on the fake clock.
        val backend = ScriptedBackend(onInfer = { clock.advanceMillis(1_000) })
        val events = execute(backend, manifest(iterations = 10, warmup = 0, capMillis = 2_500), clock)

        val completed = events.last().shouldBeInstanceOf<RunProgress.Completed>()
        completed.observations.iterations.map { it.index } shouldContainExactly listOf(0, 1, 2)
        completed.observations.stoppedEarlyReason shouldBe "sustainedDurationMillis=2500 reached after 3 iterations"
    }

    "the requested and executed delegate are reported as the backend loaded them" {
        val backend = ScriptedBackend(effective = Delegate.CPU)
        val events = execute(backend, manifest(delegate = Delegate.GPU))

        events.filterIsInstance<RunProgress.ModelLoaded>().single().effectiveDelegate shouldBe Delegate.CPU
        val loaded = events.last().shouldBeInstanceOf<RunProgress.Completed>().loaded
        loaded.requestedDelegate shouldBe Delegate.GPU
        loaded.effectiveDelegate shouldBe Delegate.CPU
    }

    "the accelerator named in the manifest reaches the backend, and none is passed otherwise" {
        val named = ScriptedBackend(caps = capabilities(setOf(Delegate.CPU, Delegate.NNAPI)))
        execute(named, manifest(delegate = Delegate.NNAPI, accelerator = "qti-hta")).last().shouldBeInstanceOf<RunProgress.Completed>()
        named.acceleratorRequested shouldBe "qti-hta"

        val unnamed = ScriptedBackend()
        execute(unnamed, manifest()).last().shouldBeInstanceOf<RunProgress.Completed>()
        unnamed.acceleratorRequested shouldBe null
    }
})

private fun execute(
    backend: ScriptedBackend,
    manifest: RunManifest,
    clock: Clock = SteppingClock(),
): List<RunProgress> = execute(mapOf(backend.id to backend), manifest, clock)

private fun execute(
    backends: Map<RuntimeId, InferenceBackend>,
    manifest: RunManifest,
    clock: Clock = SteppingClock(),
): List<RunProgress> {
    var events: List<RunProgress> = emptyList()
    runTest {
        events = ExecuteRunUseCase(backends, QuietThermal, QuietPower, FixedMemory, clock)(manifest).toList()
    }
    // Started precedes everything once the backend is accepted; drop it so tests read the outcome.
    return events.filterNot { it is RunProgress.Started }
}

private fun digestFor(index: Int): Sha256 = Sha256(index.toString(16).padStart(64, '0'))

private fun capabilities(
    delegates: Set<Delegate> = setOf(Delegate.CPU, Delegate.GPU),
) = BackendCapabilities(
    runtime = RuntimeId.MEDIAPIPE,
    available = true,
    unavailableReason = null,
    runtimeVersion = "0.10.27",
    supportedFormats = setOf(ModelFormat.LITERT),
    supportedDelegates = delegates,
    maxThreads = 8,
    supportsTextGeneration = true,
    supportsTensorInference = false,
    supportsLogitProjection = false,
)

private fun manifest(
    iterations: Int = 3,
    warmup: Int = 0,
    capMillis: Long? = null,
    format: ModelFormat = ModelFormat.LITERT,
    delegate: Delegate = Delegate.CPU,
    accelerator: String? = null,
) = RunManifest(
    manifestId = ManifestId("a".repeat(64)),
    model = ModelRef(format, Sha256("b".repeat(64)), null, QuantizationSpec.INT8, null),
    backend = BackendRef(RuntimeId.MEDIAPIPE, SemVer(0, 10, 27), delegate, threadCount = 4, accelerator = accelerator),
    workload = Workload.TextGeneration(listOf("Summarise the following paragraph in one sentence."), maxNewTokens = 16),
    schedule = RunSchedule(iterations, warmup, sustainedDurationMillis = capMillis),
    seed = 42,
    toolVersion = SemVer(0, 1, 0),
)

/**
 * A backend whose behaviour is scripted per call. [failAt] counts calls to [infer]
 * from zero across warm-up and measured iterations alike, so a failure can be
 * placed in either phase.
 */
private class ScriptedBackend(
    private val caps: BackendCapabilities = capabilities(),
    private val loadFailure: Exception? = null,
    private val failAt: Set<Int> = emptySet(),
    private val failMessage: String = "boom",
    private val failWith: () -> Exception = { IllegalStateException(failMessage) },
    private val effective: Delegate? = null,
    private val onInfer: () -> Unit = {},
) : InferenceBackend {
    override val id = RuntimeId.MEDIAPIPE
    val calls = mutableListOf<Int>()
    var loads = 0
    var unloads = 0
    var acceleratorRequested: String? = null

    override suspend fun capabilities() = caps

    override suspend fun load(model: ModelRef, delegate: Delegate, threadCount: Int, accelerator: String?): LoadedModel {
        loads++
        acceleratorRequested = accelerator
        loadFailure?.let { throw it }
        return object : LoadedModel {
            override val ref = model
            override val requestedDelegate = delegate
            override val effectiveDelegate = effective ?: delegate
            override val runtimeVersionObserved = "0.10.27"
        }
    }

    override suspend fun infer(model: LoadedModel, input: WorkloadInput): InferenceResult {
        val call = calls.size
        calls += input.iterationIndex
        onInfer()
        if (call in failAt) throw failWith()
        return InferenceResult(
            outputSha256 = digestFor(input.iterationIndex),
            logitProjection = null,
            timeToFirstTokenMicros = 1_000,
            totalMicros = 100_000,
            prefillTokens = 10,
            decodeTokens = 16,
        )
    }

    override suspend fun unload(model: LoadedModel) {
        unloads++
    }
}

private class SteppingClock : Clock {
    private var nanos = 0L
    fun advanceMillis(ms: Long) { nanos += ms * 1_000_000 }
    override fun epochMillis() = 1_800_000_000_000L
    override fun monotonicNanos() = nanos
}

private object QuietThermal : ThermalTelemetrySource {
    override fun stream(): Flow<ThermalSample> = emptyFlow()
    override suspend fun halVersion(): String? = null
}

private object QuietPower : PowerTelemetrySource {
    override fun stream(): Flow<PowerSample> = emptyFlow()
}

private object FixedMemory : MemoryTelemetrySource {
    override suspend fun snapshot() = MemorySnapshot(pssKb = 100_000, nativeHeapKb = 50_000, rssKb = null, availableSystemKb = 4_000_000)
}
