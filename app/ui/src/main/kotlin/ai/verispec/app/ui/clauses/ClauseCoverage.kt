package ai.verispec.app.ui.clauses

import ai.verispec.app.ui.common.SectionTitle
import ai.verispec.app.ui.common.StatusChip
import ai.verispec.app.ui.common.color
import ai.verispec.app.ui.theme.StatusColors
import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.ClauseCoverage
import ai.verispec.core.domain.model.CoverageStatus
import ai.verispec.core.domain.model.RegulatoryFramework
import ai.verispec.core.domain.port.EvidenceRepository
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FrameworkCoverage(
    val framework: RegulatoryFramework,
    val clauses: List<ClauseCoverage>,
) {
    val satisfied: Int get() = clauses.count { it.status == CoverageStatus.SATISFIED }
    val partial: Int get() = clauses.count { it.status == CoverageStatus.PARTIAL }
    val notCovered: Int get() = clauses.count { it.status == CoverageStatus.NOT_COVERED }
}

data class ClauseCoverageState(
    val loading: Boolean = true,
    val bundleLabel: String = "",
    val frameworks: List<FrameworkCoverage> = emptyList(),
    val totalGaps: Int = 0,
)

@HiltViewModel
class ClauseCoverageViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: EvidenceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ClauseCoverageState())
    val state: StateFlow<ClauseCoverageState> = _state.asStateFlow()

    init {
        val id = BundleId(checkNotNull(savedState.get<String>("bundleId")))
        viewModelScope.launch {
            val bundle = repository.load(id)
            if (bundle == null) {
                _state.value = ClauseCoverageState(loading = false)
            } else {
                val grouped = bundle.clauseCoverage.groupBy { it.framework }
                    .map { (fw, list) -> FrameworkCoverage(fw, list.sortedBy { it.status.ordinal * -1 }) } // gaps first
                    .sortedBy { it.framework.ordinal }
                _state.value = ClauseCoverageState(
                    loading = false,
                    bundleLabel = bundle.manifest.label.ifBlank { bundle.bundleId.short },
                    frameworks = grouped,
                    totalGaps = bundle.clauseCoverage.count { it.status != CoverageStatus.SATISFIED },
                )
            }
        }
    }
}

/** Gaps are listed before coverage in every framework, and counted at the top. */
@Composable
fun ClauseCoverageScreen(viewModel: ClauseCoverageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Clause coverage · ${state.bundleLabel}", style = MaterialTheme.typography.headlineSmall)
            if (!state.loading) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (state.totalGaps > 0) StatusColors.notCovered else StatusColors.satisfied),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Text(
                        if (state.totalGaps > 0) "${state.totalGaps} clause(s) NOT fully evidenced by this bundle" else "Every catalogued clause is evidenced",
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        state.frameworks.forEach { fw ->
            item {
                SectionTitle(fw.framework.displayName)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip("${fw.notCovered} not covered", StatusColors.notCovered)
                    StatusChip("${fw.partial} partial", StatusColors.partial)
                    StatusChip("${fw.satisfied} satisfied", StatusColors.satisfied)
                }
            }
            items(fw.clauses, key = { it.clauseId.value }) { c -> ClauseRow(c) }
        }
    }
}

@Composable
private fun ClauseRow(c: ClauseCoverage) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(c.status.name.replace('_', ' '), c.status.color())
                Text(c.reference, style = MaterialTheme.typography.labelLarge)
            }
            Text(c.title, style = MaterialTheme.typography.bodyMedium)
            c.gapDescription?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.notCovered)
            }
            if (c.satisfied.isNotEmpty()) {
                Text(
                    "Evidenced: " + c.satisfied.sorted().joinToString { it.name.lowercase().replace('_', ' ') },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
