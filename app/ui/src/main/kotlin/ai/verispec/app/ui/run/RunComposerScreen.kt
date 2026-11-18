package ai.verispec.app.ui.run

import ai.verispec.app.model.StarterModel
import ai.verispec.app.model.StarterModels
import ai.verispec.app.ui.common.KeyValueRow
import ai.verispec.app.ui.common.SectionTitle
import ai.verispec.app.ui.common.StatusChip
import ai.verispec.app.ui.theme.StatusColors
import ai.verispec.core.domain.model.AcceleratorDevice
import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.ModelFormat
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.model.ScreenPolicy
import ai.verispec.core.domain.model.ThermalStatus
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Run Composer: build a RunManifest and validate it before committing device
 * time. Deliberately plain: every field is visible, nothing is hidden behind
 * "advanced" toggles, because every field is part of the hashed identity.
 */
@Composable
fun RunComposerScreen(state: RunUiState.Configuring, onIntent: (RunIntent) -> Unit) {
    val form = state.form
    fun update(block: ManifestForm.() -> ManifestForm) = onIntent(RunIntent.UpdateForm(form.block()))

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onIntent(RunIntent.ImportModel(uri))
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Run Composer", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Every field below is part of the manifest hash. Change one and it is a different run.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("Backends discovered on this device")
        if (state.capabilities.isEmpty()) {
            Text("Probing…", style = MaterialTheme.typography.bodySmall)
        }
        state.capabilities.forEach { cap ->
            KeyValueRow(
                cap.runtime.name,
                "${cap.runtimeVersion ?: "?"} · ${cap.supportedDelegates.joinToString("/")} · ${cap.supportedFormats.joinToString("/")}",
            )
            if (cap.accelerators.isNotEmpty()) {
                KeyValueRow("  NNAPI devices", cap.accelerators.joinToString(", ") { "${it.name} (${it.type.name.lowercase()})" })
            }
        }
        OutlinedButton(onClick = { onIntent(RunIntent.RefreshCapabilities) }) { Text("Re-probe backends") }

        SectionTitle("Model")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !form.importing) {
                Text(if (form.importing) "Fetching…" else "Import model file")
            }
            Text(form.modelDisplayName.ifBlank { "no model" }, style = MaterialTheme.typography.bodySmall)
        }
        StarterModelDropdown(enabled = !form.importing) { onIntent(RunIntent.UseStarterModel(it.id)) }
        if (form.modelSha256.isNotBlank()) {
            KeyValueRow("sha256", form.modelSha256.take(16) + "…", mono = true)
            KeyValueRow("size", "%.1f MB".format(form.modelSizeBytes / 1_048_576.0))
        }
        EnumDropdown("Format", ModelFormat.entries, form.modelFormat) { update { copy(modelFormat = it) } }
        OutlinedTextField(form.quantScheme, { v -> update { copy(quantScheme = v) } }, label = { Text("Quantization scheme (Q4_K_M, INT8, FP16…)") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = form.quantIsFloat, onCheckedChange = { v -> update { copy(quantIsFloat = v) } })
            Text("Floating-point reference model", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedTextField(form.parameterCount, { v -> update { copy(parameterCount = v) } }, label = { Text("Parameter count (optional)") }, modifier = Modifier.fillMaxWidth())

        SectionTitle("Backend")
        EnumDropdown("Runtime", RuntimeId.entries, form.runtime) { update { copy(runtime = it) } }
        EnumDropdown("Delegate", Delegate.entries, form.delegate) { update { copy(delegate = it) } }
        if (form.delegate == Delegate.NNAPI) {
            AcceleratorDropdown(
                devices = state.capabilities.firstOrNull { it.runtime == form.runtime }?.accelerators.orEmpty(),
                selected = form.accelerator,
            ) { update { copy(accelerator = it) } }
        }
        OutlinedTextField(form.runtimeVersion, { v -> update { copy(runtimeVersion = v) } }, label = { Text("Runtime version declared (e.g. 1.4.0)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(form.threads, { v -> update { copy(threads = v) } }, label = { Text("Threads") }, modifier = Modifier.fillMaxWidth())

        SectionTitle("Workload")
        EnumDropdown("Kind", WorkloadKind.entries, form.workloadKind) { update { copy(workloadKind = it) } }
        when (form.workloadKind) {
            WorkloadKind.TEXT_GENERATION -> {
                OutlinedTextField(form.prompts, { v -> update { copy(prompts = v) } }, label = { Text("Prompts (one per line)") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(form.maxNewTokens, { v -> update { copy(maxNewTokens = v) } }, label = { Text("Max new tokens") }, modifier = Modifier.fillMaxWidth())
                Text("Greedy decoding is enforced (temperature 0, topK 1) so output digests are reproducible.", style = MaterialTheme.typography.bodySmall)
            }
            WorkloadKind.TENSOR_INFERENCE -> {
                OutlinedTextField(form.tensorShape, { v -> update { copy(tensorShape = v) } }, label = { Text("Input shape (e.g. 1,224,224,3)") }, modifier = Modifier.fillMaxWidth())
                Text("Input values are generated from the seed (SplitMix64), identical on every device.", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionTitle("Schedule")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(form.iterations, { v -> update { copy(iterations = v) } }, label = { Text("Iterations") }, modifier = Modifier.weight(1f))
            OutlinedTextField(form.warmup, { v -> update { copy(warmup = v) } }, label = { Text("Warmup") }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(form.delayMillis, { v -> update { copy(delayMillis = v) } }, label = { Text("Delay ms") }, modifier = Modifier.weight(1f))
            OutlinedTextField(form.sustainedMinutes, { v -> update { copy(sustainedMinutes = v) } }, label = { Text("Cap (min)") }, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(form.seed, { v -> update { copy(seed = v) } }, label = { Text("Seed") }, modifier = Modifier.fillMaxWidth())

        SectionTitle("Start conditions")
        Text(
            "The run waits until the device is in this state, and it has held for the whole window, before loading the model. " +
                "A warm, charging or lit-up start measures something different from a cold one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Wait for start conditions", form.gateEnabled) { v -> update { copy(gateEnabled = v) } }
        if (form.gateEnabled) {
            EnumDropdown("Thermal status at most", GATE_STATUSES, form.gateThermalStatus) { update { copy(gateThermalStatus = it) } }
            OutlinedTextField(
                form.gateMinHeadroomMargin,
                { v -> update { copy(gateMinHeadroomMargin = v) } },
                label = { Text("Min thermal headroom margin, 0–1 (blank skips)") },
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Require unplugged (charging adds heat)", form.gateRequireUnplugged) { v -> update { copy(gateRequireUnplugged = v) } }
            OutlinedTextField(form.gateMinBatteryPercent, { v -> update { copy(gateMinBatteryPercent = v) } }, label = { Text("Min battery %") }, modifier = Modifier.fillMaxWidth())
            EnumDropdown("Screen", ScreenPolicy.entries, form.gateScreen) { update { copy(gateScreen = it) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.gateStabilizationSeconds, { v -> update { copy(gateStabilizationSeconds = v) } }, label = { Text("Hold (s)") }, modifier = Modifier.weight(1f))
                OutlinedTextField(form.gateMaxWaitMinutes, { v -> update { copy(gateMaxWaitMinutes = v) } }, label = { Text("Max wait (min)") }, modifier = Modifier.weight(1f))
            }
            SwitchRow("If not met in time, run anyway and mark it", form.gateProceedIfUnmet) { v -> update { copy(gateProceedIfUnmet = v) } }
        }
        OutlinedTextField(form.label, { v -> update { copy(label = v) } }, label = { Text("Label") }, modifier = Modifier.fillMaxWidth())

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (state.errors.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.errors.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(if (e.isWarning) "WARN" else "ERROR", if (e.isWarning) StatusColors.partial else StatusColors.notCovered)
                            Text("${e.field}: ${e.message}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        state.manifestPreviewId?.let {
            Text("manifestId $it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onIntent(RunIntent.Validate) }) { Text("Validate") }
            Button(onClick = { onIntent(RunIntent.Start) }, enabled = !form.importing) { Text("Start run") }
        }
    }
}

/** For operators without a model file: pick a pinned public model, downloaded and hash-checked on selection. */
@Composable
private fun StarterModelDropdown(enabled: Boolean, onSelect: (StarterModel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Or use a starter model") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            StarterModels.all.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("${m.title} · %.1f MB".format(m.sizeBytes / 1_048_576.0))
                            Text(m.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { onSelect(m); open = false },
                )
            }
        }
    }
}

/**
 * Binds the NNAPI delegate to one device, or leaves the choice to NNAPI (blank). NNAPI does
 * not report its own choice, so only a named device is recorded as the one used.
 */
@Composable
private fun AcceleratorDropdown(devices: List<AcceleratorDevice>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("NNAPI device: ${selected.ifBlank { "let NNAPI choose (not recorded)" }}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Let NNAPI choose") }, onClick = { onSelect(""); open = false })
            devices.forEach { d ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(d.name)
                            Text(
                                listOfNotNull(d.type.name.lowercase(), d.version, d.featureLevel?.let { "feature level $it" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onSelect(d.name); open = false },
                )
            }
        }
        if (devices.isEmpty()) {
            Text(
                "This device lists no NNAPI devices, so none can be named.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Statuses a gate can sensibly require; anything hotter is not a start condition. */
private val GATE_STATUSES = listOf(ThermalStatus.NONE, ThermalStatus.LIGHT, ThermalStatus.MODERATE)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun <T : Enum<T>> EnumDropdown(label: String, values: List<T>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${selected.name}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            values.forEach { v ->
                DropdownMenuItem(text = { Text(v.name) }, onClick = { onSelect(v); open = false })
            }
        }
    }
}
