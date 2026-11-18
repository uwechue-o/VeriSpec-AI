package ai.verispec.app.ui.bundles

import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.BundleSummary
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.ExportFormat
import ai.verispec.core.domain.model.SignatureStatus
import ai.verispec.core.domain.model.ThermalEnvelope
import ai.verispec.core.domain.model.VerificationReport
import ai.verispec.core.domain.port.EvidenceRepository
import ai.verispec.core.domain.usecase.ComputeThermalEnvelopeUseCase
import ai.verispec.core.domain.usecase.ExportBundleUseCase
import ai.verispec.core.domain.usecase.VerifyBundleUseCase
import ai.verispec.core.evidence.verify.JcaSignatureVerifier
import ai.verispec.data.persistence.RoomEvidenceRepository
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class BundleListViewModel @Inject constructor(repository: EvidenceRepository) : ViewModel() {
    val bundles: StateFlow<List<BundleSummary>> =
        repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** A certificate in the attestation chain, rendered for humans. */
data class ChainEntry(val index: Int, val subject: String, val issuer: String, val notAfter: String, val sigAlg: String)

sealed interface BundleDetailState {
    data object Loading : BundleDetailState
    data class Missing(val id: String) : BundleDetailState
    data class Loaded(
        val bundle: EvidenceBundle,
        val envelope: ThermalEnvelope?,
        val verification: VerificationReport?,
        val chain: List<ChainEntry>,
        val exporting: Boolean,
    ) : BundleDetailState
}

sealed interface BundleDetailEffect {
    data class Message(val text: String) : BundleDetailEffect
}

@HiltViewModel
class BundleDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: EvidenceRepository,
    private val envelopeUseCase: ComputeThermalEnvelopeUseCase,
    private val verify: VerifyBundleUseCase,
    private val export: ExportBundleUseCase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val bundleId = BundleId(checkNotNull(savedState.get<String>("bundleId")))

    private val _state = MutableStateFlow<BundleDetailState>(BundleDetailState.Loading)
    val state: StateFlow<BundleDetailState> = _state.asStateFlow()

    private val _effects = Channel<BundleDetailEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            val bundle = repository.load(bundleId)
            if (bundle == null) {
                _state.value = BundleDetailState.Missing(bundleId.value)
            } else {
                _state.value = BundleDetailState.Loaded(
                    bundle = bundle,
                    envelope = envelopeUseCase(bundle.observations),
                    verification = null,
                    chain = describeChain(bundle),
                    exporting = false,
                )
            }
        }
    }

    fun onVerify() {
        val current = _state.value as? BundleDetailState.Loaded ?: return
        viewModelScope.launch {
            val report = withContext(Dispatchers.Default) { verify(current.bundle) }
            _state.value = current.copy(verification = report)
            (repository as? RoomEvidenceRepository)?.markSignatureStatus(
                bundleId,
                when {
                    report.isTrustworthy && report.hardwareBacked -> SignatureStatus.VALID
                    report.isTrustworthy -> SignatureStatus.NO_HARDWARE_BACKING
                    else -> SignatureStatus.INVALID
                },
            )
        }
    }

    /** Writes canonical JSON to a SAF-picked location. PDF is host-side by design. */
    fun onExportJson(target: Uri) {
        val current = _state.value as? BundleDetailState.Loaded ?: return
        _state.value = current.copy(exporting = true)
        viewModelScope.launch {
            val result = runCatching {
                val artifact = export(bundleId, ExportFormat.JSON)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target)?.use { it.write(artifact.bytes) } ?: error("cannot open $target")
                }
                artifact.bytes.size
            }
            (_state.value as? BundleDetailState.Loaded)?.let { _state.value = it.copy(exporting = false) }
            _effects.send(
                result.fold(
                    { BundleDetailEffect.Message("Exported $it bytes of canonical JSON") },
                    { BundleDetailEffect.Message("Export failed: ${it.message}") },
                ),
            )
        }
    }

    fun onDelete(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.delete(bundleId)
            onDone()
        }
    }

    private fun describeChain(bundle: EvidenceBundle): List<ChainEntry> =
        bundle.attestationChain.mapIndexed { i, cert ->
            runCatching {
                val x = JcaSignatureVerifier.parse(cert)
                ChainEntry(i, x.subjectX500Principal.name, x.issuerX500Principal.name, x.notAfter.toString(), x.sigAlgName)
            }.getOrElse { ChainEntry(i, "unparseable (${cert.der.size} bytes)", "", "", "") }
        }
}
