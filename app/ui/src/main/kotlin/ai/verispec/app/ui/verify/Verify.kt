package ai.verispec.app.ui.verify

import ai.verispec.app.ui.common.KeyValueRow
import ai.verispec.app.ui.common.SectionTitle
import ai.verispec.app.ui.common.StatusChip
import ai.verispec.app.ui.theme.StatusColors
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.VerificationReport
import ai.verispec.core.domain.port.EvidenceRepository
import ai.verispec.core.domain.usecase.VerifyBundleUseCase
import ai.verispec.core.evidence.BundleCodec
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class VerifyState(
    val busy: Boolean = false,
    val fileName: String? = null,
    val bundle: EvidenceBundle? = null,
    val report: VerificationReport? = null,
    val error: String? = null,
    val importedMessage: String? = null,
)

/**
 * Verify a foreign bundle. Everything here uses only the bundle's own bytes and
 * public certificates: if nobody but the producer can verify a bundle, it isn't
 * evidence.
 */
@HiltViewModel
class VerifyViewModel @Inject constructor(
    private val verify: VerifyBundleUseCase,
    private val repository: EvidenceRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(VerifyState())
    val state: StateFlow<VerifyState> = _state.asStateFlow()

    fun onPick(uri: Uri) {
        _state.value = VerifyState(busy = true)
        viewModelScope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot open file")
                }
                val bundle = BundleCodec.decode(bytes)
                bundle to withContext(Dispatchers.Default) { verify(bundle) }
            }
            _state.value = result.fold(
                { (bundle, report) ->
                    VerifyState(fileName = uri.lastPathSegment, bundle = bundle, report = report)
                },
                { VerifyState(error = "Could not read bundle: ${it.message}") },
            )
        }
    }

    /** Imports a foreign bundle into the local store so it shows up on the Fleet tab. */
    fun onImport() {
        val bundle = _state.value.bundle ?: return
        viewModelScope.launch {
            repository.save(bundle, imported = true)
            _state.value = _state.value.copy(importedMessage = "Imported ${bundle.bundleId.short}… into the local store")
        }
    }
}

@Composable
fun VerifyScreen(viewModel: VerifyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onPick(uri)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Verify a bundle", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Checks the canonical content hash, the detached signature against the leaf certificate, the attestation chain, and whether the challenge is bound to this manifest.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { picker.launch(arrayOf("application/json", "*/*")) }, enabled = !state.busy) {
            Text(if (state.busy) "Verifying…" else "Open bundle JSON")
        }

        state.error?.let { StatusChip(it, StatusColors.notCovered) }

        val report = state.report
        val bundle = state.bundle
        if (report != null && bundle != null) {
            SectionTitle("Result")
            StatusChip(
                if (report.isTrustworthy) "TRUSTWORTHY" else "NOT TRUSTWORTHY",
                if (report.isTrustworthy) StatusColors.satisfied else StatusColors.notCovered,
            )
            KeyValueRow("bundle", bundle.bundleId.short + "…", mono = true)
            KeyValueRow("manifest", bundle.manifest.manifestId.short + "…", mono = true)
            KeyValueRow("produced on", "${bundle.device.manufacturer} ${bundle.device.deviceModel} (${bundle.device.soc.model})")
            KeyValueRow("canonical hash", if (report.canonicalHashMatches) "matches" else "MISMATCH")
            KeyValueRow("signature", if (report.signatureValid) "valid" else "INVALID")
            KeyValueRow("attestation chain", if (report.attestationChainValid) "valid" else "INVALID")
            KeyValueRow("hardware-backed", report.hardwareBacked.toString())
            KeyValueRow("StrongBox", report.strongBox.toString())
            KeyValueRow("challenge bound to manifest", report.challengeBoundToManifest.toString())
            KeyValueRow("schema", "${bundle.schemaVersion}${if (report.schemaVersionSupported) "" else " (UNSUPPORTED)"}")

            if (report.issues.isNotEmpty()) {
                SectionTitle("Issues")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        report.issues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = viewModel::onImport) { Text("Import into fleet") }
            }
            state.importedMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Note: the chain must end at one of Google's published hardware attestation roots. Revocation and verified-boot state are not checked yet. For evidence you rely on, re-check the file on your own computer with the verispec CLI.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
