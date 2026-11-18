package ai.verispec.app.ui.run

import ai.verispec.app.ui.common.DecayChart
import ai.verispec.app.ui.common.KeepScreenOnDimmed
import ai.verispec.app.ui.common.KeyValueRow
import ai.verispec.app.ui.common.SectionTitle
import ai.verispec.app.ui.common.StatusChip
import ai.verispec.app.ui.common.color
import ai.verispec.app.ui.theme.StatusColors
import ai.verispec.core.domain.model.ScreenPolicy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Live Run: real-time decay chart, thermal state, throttle events. */
@Composable
fun LiveRunScreen(state: RunUiState, onIntent: (RunIntent) -> Unit, onOpenBundle: (String) -> Unit, onNewRun: () -> Unit) {
    // Held from the gate's wait through the last iteration, so the screen state is the same throughout.
    val dimmed = state is RunUiState.Running && state.screenPolicy == ScreenPolicy.ON_DIMMED
    KeepScreenOnDimmed(active = dimmed)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            is RunUiState.Running if state.awaitingPreconditions -> {
                Text("Waiting for start conditions", style = MaterialTheme.typography.headlineSmall)
                Text(state.manifestId.value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(state.stageDetail ?: "Checking device conditions…", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (dimmed) {
                        "The model loads only once every condition has held for the whole window. Keep VeriSpec open: it " +
                            "holds the screen on at minimum brightness until the run ends."
                    } else {
                        "The model loads only once every condition has held for the whole window. If the screen must be off, " +
                            "turn it off now: the run starts by itself and its progress shows in the notification."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { onIntent(RunIntent.Cancel) }) { Text("Cancel run") }
            }

            is RunUiState.Running -> {
                Text("Live run", style = MaterialTheme.typography.headlineSmall)
                Text(state.manifestId.value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(state.liveThermal.name, state.liveThermal.color())
                    Text("stage: ${state.stage}", style = MaterialTheme.typography.bodyMedium)
                }
                KeyValueRow("iteration", "${state.currentIteration} / ${state.totalIterations}")
                KeyValueRow("${state.throughputUnit} (last)", "%.2f".format(state.liveTokensPerSecond))
                KeyValueRow("${state.throughputUnit} (peak)", "%.2f".format(state.peakTokensPerSecond))
                KeyValueRow("thermal headroom", state.liveHeadroom?.let { "%.2f".format(it) } ?: "n/a")
                KeyValueRow("throttled iterations (LIGHT+)", state.throttledIterations.toString())

                SectionTitle("Decay curve")
                DecayChart(state.decayChart, label = state.throughputUnit, peak = state.peakTokensPerSecond.takeIf { it > 0 })
                Text(
                    "Red markers are iterations that started or ended in a throttling thermal state (MODERATE or worse).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { onIntent(RunIntent.Cancel) }) { Text("Cancel run") }
            }

            is RunUiState.Complete -> {
                Text("Run complete", style = MaterialTheme.typography.headlineSmall)
                StatusChip("SIGNED BUNDLE", StatusColors.satisfied)
                KeyValueRow("bundle", state.bundleId.short + "…", mono = true)
                KeyValueRow("iterations", state.iterations.toString())
                KeyValueRow("throttled", state.throttled.toString())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenBundle(state.bundleId.value) }) { Text("Open bundle") }
                    OutlinedButton(onClick = onNewRun) { Text("New run") }
                }
            }

            is RunUiState.Failed -> {
                Text("Run failed", style = MaterialTheme.typography.headlineSmall)
                StatusChip("FAILED", StatusColors.notCovered)
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Failures are recorded, not hidden: a backend that cannot run this manifest is itself a finding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onNewRun) { Text("Back to composer") }
            }

            RunUiState.Idle, is RunUiState.Configuring -> {
                Text("No run in progress", style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(onClick = onNewRun) { Text("Compose a run") }
            }
        }
    }
}
