package ai.verispec.app.ui.fleet

import ai.verispec.app.ui.common.KeyValueRow
import ai.verispec.app.ui.common.SectionTitle
import ai.verispec.app.ui.common.StatusChip
import ai.verispec.app.ui.theme.StatusColors
import ai.verispec.core.domain.model.BundleSummary
import ai.verispec.core.domain.model.QuantizationDelta
import ai.verispec.core.domain.port.EvidenceRepository
import ai.verispec.core.domain.usecase.ComputeQuantizationDeltaUseCase
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Bundles sharing one manifest, i.e. the same run executed on several devices. */
data class FleetGroup(
    val manifestId: String,
    val label: String,
    val bundles: List<BundleSummary>,
    /** Pairwise digest agreement vs. the first (reference) bundle, by bundle id. */
    val divergence: Map<String, QuantizationDelta>,
) {
    val deviceCount: Int get() = bundles.map { it.deviceFingerprint }.toSet().size
}

data class FleetState(val groups: List<FleetGroup> = emptyList(), val imported: Int = 0)

/**
 * On-device fleet summary: which manifests have been run on more than one
 * device, and whether their output digests agree. The full divergence report
 * (cosine/L∞/KL over logit projections across N devices) is computed on the
 * JVM side by tools/analysis; this is the quick look.
 */
@HiltViewModel
class FleetViewModel @Inject constructor(
    private val repository: EvidenceRepository,
    private val delta: ComputeQuantizationDeltaUseCase,
) : ViewModel() {

    val state: StateFlow<FleetState> = repository.observeAll()
        .map { summaries ->
            val groups = summaries.groupBy { it.manifestId.value }
                .map { (manifestId, list) ->
                    val sorted = list.sortedBy { it.createdAtEpochMillis }
                    val divergence = if (sorted.size >= 2) computeDivergence(sorted) else emptyMap()
                    FleetGroup(manifestId, sorted.first().label, sorted, divergence)
                }
                .sortedByDescending { it.bundles.size }
            FleetState(groups, summaries.count { it.imported })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FleetState())

    private suspend fun computeDivergence(sorted: List<BundleSummary>): Map<String, QuantizationDelta> {
        val reference = repository.load(sorted.first().bundleId) ?: return emptyMap()
        return sorted.drop(1).mapNotNull { s ->
            val other = repository.load(s.bundleId) ?: return@mapNotNull null
            s.bundleId.value to delta(other.observations.outputDigests, reference.observations.outputDigests)
        }.toMap()
    }
}

@Composable
fun FleetScreen(onOpen: (String) -> Unit, viewModel: FleetViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Fleet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Bundles grouped by manifest. Import bundles from other devices on the Verify tab; identical manifests line up here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyValueRow("imported bundles", state.imported.toString())
        }
        items(state.groups, key = { it.manifestId }) { g ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(g.label.ifBlank { g.manifestId.take(12) }, style = MaterialTheme.typography.titleMedium)
                    Text(g.manifestId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    KeyValueRow("bundles / devices", "${g.bundles.size} / ${g.deviceCount}")
                    if (g.bundles.size < 2) {
                        StatusChip("single device: divergence not measurable", StatusColors.neutral)
                    } else {
                        SectionTitle("Divergence vs. first bundle")
                    }
                    g.bundles.forEach { b ->
                        val d = g.divergence[b.bundleId.value]
                        Column(Modifier.fillMaxWidth().clickable { onOpen(b.bundleId.value) }.padding(vertical = 4.dp)) {
                            Text("${b.socModel} · ${b.delegate} · ${b.deviceFingerprint.take(40)}", style = MaterialTheme.typography.bodySmall)
                            if (d != null) {
                                val agreement = d.digestAgreementRate
                                StatusChip(
                                    "digest agreement %.0f %% over ${d.pairedIterations} it".format(agreement * 100),
                                    when {
                                        agreement >= 0.999 -> StatusColors.satisfied
                                        agreement >= 0.9 -> StatusColors.partial
                                        else -> StatusColors.notCovered
                                    },
                                )
                                d.meanCosineSimilarity?.let { KeyValueRow("mean cosine", "%.5f".format(it)) }
                                d.maxLInfDelta?.let { KeyValueRow("max L∞", "%.5f".format(it)) }
                            } else if (g.bundles.size >= 2) {
                                StatusChip("reference", StatusColors.neutral)
                            }
                        }
                    }
                }
            }
        }
    }
}
