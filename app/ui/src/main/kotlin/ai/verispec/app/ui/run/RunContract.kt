package ai.verispec.app.ui.run

import ai.verispec.app.ui.common.ChartPoint
import ai.verispec.core.domain.model.BackendCapabilities
import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.ModelFormat
import ai.verispec.core.domain.model.RunFailure
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.model.ScreenPolicy
import ai.verispec.core.domain.model.ThermalStatus
import android.net.Uri

enum class WorkloadKind { TEXT_GENERATION, TENSOR_INFERENCE }

/** Everything the operator can type. Strings on purpose: validation turns them into a manifest. */
data class ManifestForm(
    val label: String = "",
    val modelFileUri: String = "",
    val modelDisplayName: String = "",
    val modelSha256: String = "",
    val modelSizeBytes: Long = 0,
    val modelFormat: ModelFormat = ModelFormat.TFLITE,
    val quantScheme: String = "INT8",
    val quantIsFloat: Boolean = false,
    val parameterCount: String = "",
    val runtime: RuntimeId = RuntimeId.LITERT,
    val runtimeVersion: String = "",
    val delegate: Delegate = Delegate.CPU,
    /** NNAPI device to bind the delegate to; blank lets NNAPI choose. Ignored for other delegates. */
    val accelerator: String = "",
    val threads: String = "4",
    val workloadKind: WorkloadKind = WorkloadKind.TENSOR_INFERENCE,
    val prompts: String = "Summarise the following paragraph in one sentence.",
    val maxNewTokens: String = "64",
    val tensorShape: String = "1,224,224,3",
    val iterations: String = "30",
    val warmup: String = "3",
    val delayMillis: String = "0",
    val sustainedMinutes: String = "",
    val seed: String = "42",
    // Start conditions (precondition gate). On by default: an uncontrolled start is only partial evidence.
    val gateEnabled: Boolean = true,
    val gateThermalStatus: ThermalStatus = ThermalStatus.NONE,
    val gateMinHeadroomMargin: String = "0.5",
    val gateRequireUnplugged: Boolean = true,
    val gateMinBatteryPercent: String = "50",
    val gateScreen: ScreenPolicy = ScreenPolicy.OFF,
    val gateStabilizationSeconds: String = "30",
    val gateMaxWaitMinutes: String = "15",
    val gateProceedIfUnmet: Boolean = false,
    val importing: Boolean = false,
)

data class FieldError(val field: String, val message: String, val isWarning: Boolean = false)

sealed interface RunUiState {
    data object Idle : RunUiState

    data class Configuring(
        val form: ManifestForm,
        val errors: List<FieldError>,
        val capabilities: List<BackendCapabilities>,
        val manifestPreviewId: String?,
    ) : RunUiState

    data class Running(
        val manifestId: ManifestId,
        val stage: String,
        /** Stage detail, e.g. the start conditions still unmet while the gate waits. */
        val stageDetail: String?,
        /** True while the precondition gate is waiting; no iterations have run yet. */
        val awaitingPreconditions: Boolean,
        val progress: Float,
        val currentIteration: Int,
        val totalIterations: Int,
        val liveThermal: ThermalStatus,
        val liveHeadroom: Float?,
        val liveTokensPerSecond: Double,
        /** "tok/s" or "inf/s", from the manifest's workload. */
        val throughputUnit: String,
        val decayChart: List<ChartPoint>,
        val throttledIterations: Int,
        val peakTokensPerSecond: Double,
        /** The manifest's screen policy; ON_DIMMED makes the live screen hold the display on, dimmed. */
        val screenPolicy: ScreenPolicy? = null,
    ) : RunUiState

    data class Complete(val manifestId: ManifestId, val bundleId: BundleId, val iterations: Int, val throttled: Int) : RunUiState

    data class Failed(val manifestId: ManifestId?, val reason: RunFailure?, val message: String) : RunUiState
}

sealed interface RunIntent {
    data object NewRun : RunIntent
    data class UpdateForm(val form: ManifestForm) : RunIntent
    data class ImportModel(val uri: Uri) : RunIntent
    data class UseStarterModel(val id: String) : RunIntent
    data object Validate : RunIntent
    data object Start : RunIntent
    data class Observe(val manifestId: ManifestId) : RunIntent
    data object Cancel : RunIntent
    data object RefreshCapabilities : RunIntent
}

sealed interface RunEffect {
    data class ShowError(val message: String) : RunEffect
    data class NavigateToLive(val manifestId: ManifestId) : RunEffect
    data class NavigateToBundle(val id: BundleId) : RunEffect
}
