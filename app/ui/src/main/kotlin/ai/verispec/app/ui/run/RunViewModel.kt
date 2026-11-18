package ai.verispec.app.ui.run

import ai.verispec.app.BuildConfig
import ai.verispec.app.model.ModelImporter
import ai.verispec.app.model.StarterModels
import ai.verispec.app.runner.RunScheduler
import ai.verispec.app.runner.RunWorker
import ai.verispec.app.ui.common.ChartPoint
import ai.verispec.app.ui.common.throughputUnit
import ai.verispec.core.domain.math.SustainedLoad
import ai.verispec.core.domain.model.BackendRef
import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.ModelRef
import ai.verispec.core.domain.model.PreconditionPolicy
import ai.verispec.core.domain.model.QuantizationSpec
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.RunSchedule
import ai.verispec.core.domain.model.SemVer
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.model.Workload
import ai.verispec.core.domain.usecase.ManifestIdentity
import ai.verispec.core.domain.usecase.ValidateManifestUseCase
import ai.verispec.data.persistence.RunRepository
import ai.verispec.data.persistence.SettingsStore
import ai.verispec.data.persistence.db.RunEntity
import ai.verispec.data.runtime.api.BackendRegistry
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Translates intents into use cases and domain results into UI state. Holds no
 * domain logic and no Android Context. Composables get state + (RunIntent) -> Unit.
 */
@HiltViewModel
class RunViewModel @Inject constructor(
    private val identity: ManifestIdentity,
    private val validateManifest: ValidateManifestUseCase,
    private val registry: BackendRegistry,
    private val scheduler: RunScheduler,
    private val runs: RunRepository,
    private val settings: SettingsStore,
    private val importer: ModelImporter,
) : ViewModel() {

    private val _state = MutableStateFlow<RunUiState>(RunUiState.Idle)
    val state: StateFlow<RunUiState> = _state.asStateFlow()

    private val _effects = Channel<RunEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var discovery: BackendRegistry.Discovery? = null
    private var observeJob: Job? = null

    init {
        onIntent(RunIntent.NewRun)
    }

    fun onIntent(intent: RunIntent) {
        when (intent) {
            RunIntent.NewRun -> newRun()
            is RunIntent.UpdateForm -> updateForm(intent.form)
            is RunIntent.ImportModel -> importModel(intent.uri)
            is RunIntent.UseStarterModel -> useStarterModel(intent.id)
            RunIntent.Validate -> validate()
            RunIntent.Start -> start()
            is RunIntent.Observe -> observe(intent.manifestId)
            RunIntent.Cancel -> cancel()
            RunIntent.RefreshCapabilities -> refreshCapabilities()
        }
    }

    private fun newRun() {
        observeJob?.cancel()
        viewModelScope.launch {
            val s = settings.settings.first()
            val form = ManifestForm(
                iterations = s.defaultIterations.toString(),
                warmup = s.defaultWarmup.toString(),
                threads = s.defaultThreads.toString(),
            )
            _state.value = RunUiState.Configuring(form, emptyList(), discovery?.available.orEmpty(), null)
            refreshCapabilities()
        }
    }

    private fun refreshCapabilities() {
        viewModelScope.launch {
            val d = registry.discover()
            discovery = d
            val current = _state.value
            if (current is RunUiState.Configuring) {
                _state.value = current.copy(capabilities = d.available)
            }
        }
    }

    private fun updateForm(form: ManifestForm) {
        val current = _state.value as? RunUiState.Configuring ?: return
        _state.value = current.copy(form = form, errors = emptyList(), manifestPreviewId = null)
    }

    private fun importModel(uri: Uri) = loadModel("Import", { importer.import(uri) }) { it }

    /** A starter model also sets the fields it determines, so the run is valid without further typing. */
    private fun useStarterModel(id: String) {
        val starter = StarterModels.byId(id) ?: return
        loadModel("Download", { importer.fetchStarter(starter) }) { form ->
            val base = form.copy(
                quantScheme = starter.quantScheme,
                quantIsFloat = starter.isFloatingPoint,
                parameterCount = starter.parameterCount.ifBlank { form.parameterCount },
                runtime = starter.runtime,
            )
            val shape = starter.tensorShape
            if (shape == null) {
                base.copy(workloadKind = WorkloadKind.TEXT_GENERATION)
            } else {
                base.copy(workloadKind = WorkloadKind.TENSOR_INFERENCE, tensorShape = shape)
            }
        }
    }

    private fun loadModel(verb: String, fetch: suspend () -> ModelImporter.Imported, adjust: (ManifestForm) -> ManifestForm) {
        val current = _state.value as? RunUiState.Configuring ?: return
        _state.value = current.copy(form = current.form.copy(importing = true))
        viewModelScope.launch {
            runCatching { fetch() }
                .onSuccess { imported ->
                    val c = _state.value as? RunUiState.Configuring ?: return@onSuccess
                    _state.value = c.copy(
                        form = adjust(
                            c.form.copy(
                                importing = false,
                                modelFileUri = imported.fileUri,
                                modelDisplayName = imported.displayName,
                                modelSha256 = imported.sha256.hex,
                                modelSizeBytes = imported.sizeBytes,
                                modelFormat = imported.guessedFormat ?: c.form.modelFormat,
                            ),
                        ),
                        errors = emptyList(),
                    )
                }
                .onFailure { e ->
                    val c = _state.value as? RunUiState.Configuring ?: return@onFailure
                    _state.value = c.copy(form = c.form.copy(importing = false))
                    _effects.trySend(RunEffect.ShowError("$verb failed: ${e.message}"))
                }
        }
    }

    /** Returns the manifest when valid, otherwise updates the state with errors and returns null. */
    private fun buildAndValidate(): RunManifest? {
        val current = _state.value as? RunUiState.Configuring ?: return null
        val (manifest, formErrors) = ManifestFormMapper.toManifest(current.form, identity, BuildConfig.TOOL_VERSION)
        if (manifest == null) {
            _state.value = current.copy(errors = formErrors, manifestPreviewId = null)
            return null
        }
        val caps = discovery?.capabilities?.get(manifest.backend.runtime)
        val result = validateManifest(manifest, caps)
        val errors = formErrors + result.issues.map {
            FieldError(it.field, it.message, isWarning = it.severity == ai.verispec.core.domain.model.Severity.WARNING)
        }
        _state.value = current.copy(errors = errors, manifestPreviewId = manifest.manifestId.value)
        return if (result.isValid && formErrors.none { !it.isWarning }) manifest else null
    }

    private fun validate() {
        buildAndValidate()
    }

    private fun start() {
        val manifest = buildAndValidate() ?: return
        viewModelScope.launch {
            val id = scheduler.schedule(manifest)
            _effects.send(RunEffect.NavigateToLive(id))
            observe(id)
        }
    }

    private fun observe(id: ManifestId) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(scheduler.observe(id), runs.observeIterations(id), runs.observe(id)) { live, iterations, record ->
                val points = iterations.map { (r, _) -> ChartPoint(r.index.toFloat(), r.decodeTokensPerSecond.toFloat(), r.wasThrottled) }
                // Same rules as the bundle's envelope: throttling from LIGHT, peak as the best window.
                val throttled = iterations.count { it.first.thermalStatusAtStart.isThrottleOnset || it.first.thermalStatusAtEnd.isThrottleOnset }
                val peak = SustainedLoad.windowedPeak(iterations.map { it.first.decodeTokensPerSecond })
                val completedBundleId = record?.bundleId
                val workState = live.state
                when {
                    record?.status == RunEntity.STATUS_COMPLETED && completedBundleId != null ->
                        RunUiState.Complete(id, BundleId(completedBundleId), iterations.size, throttled)
                    record?.status == RunEntity.STATUS_FAILED ->
                        RunUiState.Failed(id, null, record.failureMessage ?: live.failure ?: "Run failed")
                    record?.status == RunEntity.STATUS_CANCELLED || workState == WorkInfo.State.CANCELLED ->
                        RunUiState.Failed(id, null, "Run cancelled")
                    workState == WorkInfo.State.FAILED ->
                        RunUiState.Failed(id, null, live.failure ?: "Worker failed")
                    else -> RunUiState.Running(
                        manifestId = id,
                        stage = live.stage ?: workState?.name?.lowercase() ?: "queued",
                        stageDetail = live.detail,
                        awaitingPreconditions = live.stage == RunWorker.STAGE_PRECONDITIONS,
                        progress = live.fraction,
                        currentIteration = maxOf(live.iteration, iterations.size),
                        totalIterations = if (live.total > 0) live.total else record?.manifest?.schedule?.iterations ?: 0,
                        liveThermal = live.thermal,
                        liveHeadroom = live.headroom,
                        liveTokensPerSecond = live.tokensPerSecond,
                        throughputUnit = record?.manifest?.workload?.throughputUnit() ?: "/s",
                        decayChart = points,
                        throttledIterations = throttled,
                        peakTokensPerSecond = peak,
                        screenPolicy = record?.manifest?.preconditions?.screenPolicy,
                    )
                }
            }.collect { _state.value = it }
        }
    }

    private fun cancel() {
        val id = when (val s = _state.value) {
            is RunUiState.Running -> s.manifestId
            else -> return
        }
        scheduler.cancel(id)
    }
}

/** Pure mapping from form strings to a hashed manifest. Kept out of the ViewModel so it is unit-testable. */
object ManifestFormMapper {

    fun toManifest(form: ManifestForm, identity: ManifestIdentity, toolVersion: String): Pair<RunManifest?, List<FieldError>> {
        val errors = mutableListOf<FieldError>()

        if (form.modelFileUri.isBlank() || !Sha256.isHex(form.modelSha256)) errors += FieldError("model", "Import a model file first")
        val threads = form.threads.toIntOrNull()?.takeIf { it > 0 } ?: run { errors += FieldError("threads", "Threads must be a positive integer"); 1 }
        val iterations = form.iterations.toIntOrNull()?.takeIf { it > 0 } ?: run { errors += FieldError("iterations", "Iterations must be positive"); 1 }
        val warmup = form.warmup.toIntOrNull()?.takeIf { it >= 0 } ?: run { errors += FieldError("warmup", "Warmup must be ≥ 0"); 0 }
        val delay = form.delayMillis.toLongOrNull()?.takeIf { it >= 0 } ?: run { errors += FieldError("delay", "Delay must be ≥ 0 ms"); 0L }
        val sustained = form.sustainedMinutes.trim().takeIf { it.isNotEmpty() }?.let {
            it.toDoubleOrNull()?.takeIf { m -> m > 0 }?.let { m -> (m * 60_000).toLong() }
                ?: run { errors += FieldError("sustained", "Sustained minutes must be a positive number"); null }
        }
        val seed = form.seed.toLongOrNull() ?: run { errors += FieldError("seed", "Seed must be an integer"); 0L }
        val runtimeVersion = SemVer.parseOrNull(form.runtimeVersion.ifBlank { "0.0.0" })
            ?: run { errors += FieldError("runtimeVersion", "Runtime version must look like 1.2.3"); SemVer(0, 0, 0) }

        val workload = when (form.workloadKind) {
            WorkloadKind.TEXT_GENERATION -> {
                val prompts = form.prompts.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val maxNew = form.maxNewTokens.toIntOrNull()?.takeIf { it > 0 }
                if (prompts.isEmpty()) errors += FieldError("prompts", "At least one prompt line is required")
                if (maxNew == null) errors += FieldError("maxNewTokens", "Max new tokens must be positive")
                if (prompts.isEmpty() || maxNew == null) null else Workload.TextGeneration(prompts, maxNew)
            }
            WorkloadKind.TENSOR_INFERENCE -> {
                val shape = form.tensorShape.split(',', 'x', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
                if (shape.isEmpty()) {
                    errors += FieldError("tensorShape", "Shape must be comma-separated positive integers")
                    null
                } else {
                    Workload.TensorInference(shape)
                }
            }
        }

        val preconditions = if (!form.gateEnabled) {
            null
        } else {
            val margin = form.gateMinHeadroomMargin.trim().takeIf { it.isNotEmpty() }?.let {
                it.toFloatOrNull()?.takeIf { m -> m in 0f..1f }
                    ?: run { errors += FieldError("gate.headroom", "Headroom margin must be between 0 and 1, or blank to skip"); 0f }
            }
            val battery = form.gateMinBatteryPercent.toIntOrNull()?.takeIf { it in 0..100 }
                ?: run { errors += FieldError("gate.battery", "Minimum battery must be 0 to 100"); 0 }
            val window = form.gateStabilizationSeconds.toLongOrNull()?.takeIf { it >= 0 }
                ?: run { errors += FieldError("gate.window", "Stabilization window must be ≥ 0 s"); 0L }
            val maxWait = form.gateMaxWaitMinutes.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 60_000).toLong() }
                ?: run { errors += FieldError("gate.maxWait", "Maximum wait must be a positive number of minutes"); 1L }
            PreconditionPolicy(
                requiredThermalStatus = form.gateThermalStatus,
                minHeadroomMargin = margin,
                requireUnplugged = form.gateRequireUnplugged,
                minBatteryPercent = battery,
                screenPolicy = form.gateScreen,
                stabilizationWindowMillis = window * 1_000,
                maxWaitMillis = maxWait,
                proceedIfUnmet = form.gateProceedIfUnmet,
            )
        }
        if (!form.gateEnabled) {
            errors += FieldError("preconditions", "No start-condition gate: the performance envelope will be partial evidence", isWarning = true)
        }

        if (errors.any { !it.isWarning } || workload == null) return null to errors

        val quant = QuantizationSpec(form.quantScheme.ifBlank { "UNKNOWN" }, null, form.quantIsFloat)
        val draft = RunManifest(
            manifestId = identity.placeholder,
            model = ModelRef(
                format = form.modelFormat,
                sha256 = Sha256(form.modelSha256),
                parameterCount = form.parameterCount.toLongOrNull(),
                quantization = quant,
                sourceUri = form.modelFileUri,
            ),
            backend = BackendRef(form.runtime, runtimeVersion, form.delegate, threads),
            workload = workload,
            schedule = RunSchedule(iterations, warmup, delay, sustained),
            preconditions = preconditions,
            seed = seed,
            toolVersion = SemVer.parseOrNull(toolVersion) ?: SemVer(0, 0, 0),
            label = form.label.trim(),
        )
        return identity.withComputedId(draft) to errors
    }
}
