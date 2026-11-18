package ai.verispec.app.ui.bundles

import ai.verispec.app.ui.common.ChartPoint
import ai.verispec.app.ui.common.DecayChart
import ai.verispec.app.ui.common.KeyValueRow
import ai.verispec.app.ui.common.SectionTitle
import ai.verispec.app.ui.common.StatusChip
import ai.verispec.app.ui.common.throughputUnit
import ai.verispec.app.ui.theme.StatusColors
import ai.verispec.core.domain.model.BundleSummary
import ai.verispec.core.domain.model.EnvelopeValidity
import ai.verispec.core.domain.model.HardwareSummary
import ai.verispec.core.domain.model.SignatureStatus
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BundleListScreen(onOpen: (String) -> Unit, viewModel: BundleListViewModel = hiltViewModel()) {
    val bundles by viewModel.bundles.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Bundles", style = MaterialTheme.typography.headlineSmall)
        if (bundles.isEmpty()) {
            Text("No evidence bundles yet. Compose a run to produce one.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(bundles, key = { it.bundleId.value }) { b -> BundleRow(b) { onOpen(b.bundleId.value) } }
        }
    }
}

@Composable
private fun BundleRow(b: BundleSummary, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(b.label.ifBlank { b.bundleId.short }, style = MaterialTheme.typography.titleMedium)
                StatusChip(b.signatureStatus.name, b.signatureStatus.color())
            }
            Text("${b.runtime} · ${b.delegate} · ${b.quantization} · ${b.socModel}", style = MaterialTheme.typography.bodySmall)
            Text(
                "${b.iterationCount} it · %.1f/s mean ·${b.throttledIterations} throttled · ${b.coveredClauses}/${b.totalClauses} clauses${if (b.imported) " · imported" else ""}"
                    .format(b.meanDecodeTokensPerSecond),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

fun SignatureStatus.color() = when (this) {
    SignatureStatus.VALID -> StatusColors.satisfied
    SignatureStatus.NO_HARDWARE_BACKING -> StatusColors.partial
    SignatureStatus.INVALID -> StatusColors.notCovered
    SignatureStatus.UNVERIFIED -> StatusColors.neutral
}

@Composable
fun BundleDetailScreen(
    onOpenClauses: (String) -> Unit,
    onDeleted: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: BundleDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.effects.collect { onMessage((it as BundleDetailEffect.Message).text) } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.onExportJson(uri)
    }

    when (val s = state) {
        BundleDetailState.Loading -> CircularProgressIndicator(Modifier.padding(32.dp))
        is BundleDetailState.Missing -> Text("Bundle ${s.id} not found", Modifier.padding(16.dp))
        is BundleDetailState.Loaded -> {
            val b = s.bundle
            val unit = b.manifest.workload.throughputUnit()
            var confirmDelete by remember { mutableStateOf(false) }
            if (confirmDelete) {
                // Deletion is permanent and the bundle is signed evidence: make it a deliberate act.
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete this bundle?") },
                    text = { Text("Bundle ${b.bundleId.short} and its signature will be permanently removed from this device. Export it first if you may need it.") },
                    confirmButton = {
                        TextButton(onClick = { confirmDelete = false; viewModel.onDelete(onDeleted) }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
                )
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(b.manifest.label.ifBlank { "Bundle" }, style = MaterialTheme.typography.headlineSmall)
                Text(b.bundleId.value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

                SectionTitle("Signature")
                val v = s.verification
                if (v == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip("UNVERIFIED", StatusColors.neutral)
                        Button(onClick = viewModel::onVerify) { Text("Verify now") }
                    }
                } else {
                    StatusChip(if (v.isTrustworthy) "VERIFIED" else "NOT VERIFIED", if (v.isTrustworthy) StatusColors.satisfied else StatusColors.notCovered)
                    KeyValueRow("canonical hash", if (v.canonicalHashMatches) "matches" else "MISMATCH")
                    KeyValueRow("signature", if (v.signatureValid) "valid" else "INVALID")
                    KeyValueRow("attestation chain", if (v.attestationChainValid) "valid" else "INVALID")
                    KeyValueRow("hardware-backed", v.hardwareBacked.toString())
                    KeyValueRow("StrongBox", v.strongBox.toString())
                    KeyValueRow("challenge bound to manifest", v.challengeBoundToManifest.toString())
                    v.issues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = StatusColors.notCovered) }
                }
                KeyValueRow("algorithm", b.signature.algorithm)
                KeyValueRow("key id", b.signature.keyId.short + "…", mono = true)

                SectionTitle("Attestation chain (leaf first)")
                s.chain.forEach { c ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text("#${c.index} ${c.subject}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text("issuer ${c.issuer}", style = MaterialTheme.typography.bodySmall)
                            Text("${c.sigAlg} · expires ${c.notAfter}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                SectionTitle("Provenance")
                KeyValueRow("model sha256", b.provenance.modelSha256.short + "…", mono = true)
                KeyValueRow("format / quant", "${b.provenance.modelFormat} / ${b.manifest.model.quantization.scheme}")
                KeyValueRow("runtime", "${b.provenance.runtime} ${b.provenance.runtimeVersionDeclared} (observed ${b.provenance.runtimeVersionObserved ?: "n/a"})")
                KeyValueRow("delegate", "${b.provenance.delegate} → ${b.provenance.delegateActuallyUsed}")
                KeyValueRow("NPU driver", b.provenance.npuDriverVersion ?: "not reported")
                KeyValueRow("GPU driver", b.provenance.gpuDriverVersion ?: "not reported")
                b.provenance.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }

                SectionTitle("Device")
                KeyValueRow("fingerprint", b.device.fingerprint, mono = true)
                KeyValueRow("SoC", "${b.device.soc.vendor} ${b.device.soc.model}")
                KeyValueRow("Android", "${b.device.androidRelease} (API ${b.device.apiLevel}) patch ${b.device.securityPatch}")
                KeyValueRow("RAM", "%.1f GB".format(b.device.totalRamBytes / 1_073_741_824.0))

                SectionTitle("Performance envelope")
                s.envelope?.let { e ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(e.validity.label(), e.validity.color())
                        Text(e.validity.explanation(), style = MaterialTheme.typography.bodySmall)
                    }
                    b.observations.preconditions?.let { gate ->
                        KeyValueRow("start conditions", if (gate.satisfied) "met after ${gate.waitedMillis / 1000} s" else "NOT met")
                        gate.unmetAtStart.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        gate.conditionsAtStart.batteryTemperatureDeciC?.let { KeyValueRow("battery at start", "%.1f °C".format(it / 10.0)) }
                    }
                    KeyValueRow("peak $unit (best 5-iteration median)", "%.2f".format(e.peakDecodeTokensPerSecond))
                    KeyValueRow(
                        "steady-state $unit",
                        e.steadyStateDecodeTokensPerSecond?.let { "%.2f from iteration %d".format(it, e.steadyStateFromIteration) } ?: "not settled",
                    )
                    KeyValueRow("decay", e.decayPercent?.let { "%.1f %%".format(it) } ?: "n/a (no steady state)")
                    KeyValueRow("time to first throttle", e.timeToFirstThrottleMillis?.let { "${it / 1000} s" } ?: "never")
                    KeyValueRow("throttled iterations (LIGHT+ / MODERATE+)", "${e.throttledIterations} / ${e.materiallyThrottledIterations} of ${e.iterationsAnalyzed}")
                    KeyValueRow("max thermal status", e.maxThermalStatus.name)
                    KeyValueRow("TTFT mean / p95", "%.0f / %d µs".format(e.meanTimeToFirstTokenMicros, e.p95TimeToFirstTokenMicros))
                }
                KeyValueRow("peak PSS", "%.1f MB".format(b.observations.memoryPeaks.peakPssKb / 1024.0))
                KeyValueRow("thermal samples", b.observations.thermalTimeline.size.toString())
                KeyValueRow("power samples", b.observations.powerTimeline.size.toString())
                b.observations.stoppedEarlyReason?.let { KeyValueRow("stopped early", it) }
                HardwareSummary.of(b.observations)?.let { hw ->
                    SectionTitle("Hardware telemetry")
                    hw.rows().forEach { (k, v) -> KeyValueRow(k, v) }
                }
                SectionTitle("Throughput per iteration")
                DecayChart(
                    b.observations.iterations.map { ChartPoint(it.index.toFloat(), it.decodeTokensPerSecond.toFloat(), it.wasThrottled) },
                    label = unit,
                    peak = s.envelope?.peakDecodeTokensPerSecond,
                    steadyState = s.envelope?.steadyStateDecodeTokensPerSecond,
                )
                if (TelemetrySeries.of(b.observations).isNotEmpty()) {
                    SectionTitle("Telemetry over time")
                    TelemetryCharts(b.observations)
                }

                SectionTitle("Actions")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenClauses(b.bundleId.value) }) { Text("Clause coverage") }
                    OutlinedButton(onClick = { exportLauncher.launch("verispec-bundle-${b.bundleId.short}.json") }, enabled = !s.exporting) { Text("Export JSON") }
                }
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete bundle") }
                Text("PDF rendering runs on the host: verispec report --bundle <file>.json", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun EnvelopeValidity.label(): String = when (this) {
    EnvelopeValidity.VALID -> "VALID"
    EnvelopeValidity.PRECONDITIONS_UNMET -> "UNMET"
    EnvelopeValidity.NO_PRECONDITION_GATE -> "NO GATE"
    EnvelopeValidity.INSUFFICIENT_DATA -> "TOO SHORT"
}

private fun EnvelopeValidity.explanation(): String = when (this) {
    EnvelopeValidity.VALID -> "Controlled start, enough iterations."
    EnvelopeValidity.PRECONDITIONS_UNMET -> "Start conditions were not met; not comparable with other runs."
    EnvelopeValidity.NO_PRECONDITION_GATE -> "Start state recorded but not controlled."
    EnvelopeValidity.INSUFFICIENT_DATA -> "Too few iterations to describe sustained load."
}

private fun EnvelopeValidity.color() = when (this) {
    EnvelopeValidity.VALID -> StatusColors.satisfied
    EnvelopeValidity.NO_PRECONDITION_GATE -> StatusColors.partial
    EnvelopeValidity.PRECONDITIONS_UNMET, EnvelopeValidity.INSUFFICIENT_DATA -> StatusColors.notCovered
}
