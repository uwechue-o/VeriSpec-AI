package ai.verispec.core.domain.model

enum class RegulatoryFramework(val displayName: String) {
    EU_AI_ACT("EU AI Act (Reg. 2024/1689)"),
    ISO_IEC_42001("ISO/IEC 42001:2023"),
    ISO_PAS_8800("ISO/PAS 8800:2024"),
    FDA_PCCP("FDA PCCP Guidance (AI-enabled device software)"),
}

/**
 * The kinds of evidence a bundle can carry. Clause definitions reference these,
 * and the mapper checks which ones a given bundle actually provides. Adding a
 * new kind here is a schema change; adding a clause is only a catalog change.
 */
enum class EvidenceRequirement {
    REPRODUCIBLE_MANIFEST,
    MODEL_PROVENANCE,
    RUNTIME_PROVENANCE,
    DRIVER_PROVENANCE,
    DEVICE_PROFILE,
    PERFORMANCE_ENVELOPE,
    THERMAL_TIMELINE,
    POWER_TIMELINE,
    MEMORY_ENVELOPE,
    NUMERICAL_DIVERGENCE,
    QUANTIZATION_DELTA,
    OUTPUT_PRIVACY,
    SIGNATURE,
    HARDWARE_ATTESTATION,
    CAPABILITY_GAPS_RECORDED,
}

data class ClauseDefinition(
    val id: ClauseId,
    val framework: RegulatoryFramework,
    /** Human citation, e.g. "Art. 15(1)" or "Annex IV §2(b)". */
    val reference: String,
    val title: String,
    /** Paraphrase of the obligation. Never the verbatim legal text. */
    val requirement: String,
    val evidence: Set<EvidenceRequirement>,
    /** Free-text note on how the toolkit's evidence relates to the clause. */
    val rationale: String,
)

enum class CoverageStatus { SATISFIED, PARTIAL, NOT_COVERED }

data class ClauseCoverage(
    val clauseId: ClauseId,
    val framework: RegulatoryFramework,
    val reference: String,
    val title: String,
    val status: CoverageStatus,
    val satisfied: Set<EvidenceRequirement>,
    val missing: Set<EvidenceRequirement>,
    /** Names the gap explicitly. A compliance tool that only shows green is a liability. */
    val gapDescription: String?,
)

enum class FactStatus { PRESENT, PARTIAL, ABSENT }

/**
 * What a bundle actually demonstrates, requirement by requirement. Computed from
 * the bundle content, never asserted by the operator.
 */
data class EvidenceFacts(val facts: Map<EvidenceRequirement, FactStatus>) {
    fun status(req: EvidenceRequirement): FactStatus = facts[req] ?: FactStatus.ABSENT

    companion object {
        fun derive(
            manifest: RunManifest,
            device: DeviceProfile,
            provenance: ProvenanceRecord,
            observations: Observations,
            signaturePlanned: Boolean,
            hardwareAttestationPlanned: Boolean,
            divergenceReportAttached: Boolean,
            quantizationDeltaAttached: Boolean,
        ): EvidenceFacts {
            val greedy = when (val w = manifest.workload) {
                is Workload.TextGeneration -> w.temperature == 0.0 && w.topK == 1
                is Workload.TensorInference -> true
            }
            val facts = buildMap {
                put(EvidenceRequirement.REPRODUCIBLE_MANIFEST, if (greedy) FactStatus.PRESENT else FactStatus.PARTIAL)
                put(EvidenceRequirement.MODEL_PROVENANCE, FactStatus.PRESENT)
                put(
                    EvidenceRequirement.RUNTIME_PROVENANCE,
                    if (provenance.runtimeVersionObserved != null) FactStatus.PRESENT else FactStatus.PARTIAL,
                )
                put(
                    EvidenceRequirement.DRIVER_PROVENANCE,
                    when {
                        provenance.npuDriverVersion != null && provenance.gpuDriverVersion != null -> FactStatus.PRESENT
                        provenance.npuDriverVersion != null || provenance.gpuDriverVersion != null -> FactStatus.PARTIAL
                        provenance.delegateActuallyUsed == Delegate.CPU -> FactStatus.PRESENT
                        else -> FactStatus.ABSENT
                    },
                )
                put(EvidenceRequirement.DEVICE_PROFILE, if (device.fingerprint.isNotBlank()) FactStatus.PRESENT else FactStatus.ABSENT)
                put(
                    EvidenceRequirement.PERFORMANCE_ENVELOPE,
                    // Full evidence needs a controlled start: without a satisfied gate the
                    // envelope may describe a warm or charging device, so it is only partial.
                    when {
                        observations.iterations.size >= MIN_ITERATIONS_FOR_ENVELOPE &&
                            observations.preconditions?.satisfied == true -> FactStatus.PRESENT
                        observations.iterations.isNotEmpty() -> FactStatus.PARTIAL
                        else -> FactStatus.ABSENT
                    },
                )
                put(EvidenceRequirement.THERMAL_TIMELINE, presence(observations.thermalTimeline.isNotEmpty()))
                put(EvidenceRequirement.POWER_TIMELINE, presence(observations.powerTimeline.isNotEmpty()))
                put(EvidenceRequirement.MEMORY_ENVELOPE, FactStatus.PRESENT)
                put(EvidenceRequirement.NUMERICAL_DIVERGENCE, presence(divergenceReportAttached))
                put(EvidenceRequirement.QUANTIZATION_DELTA, presence(quantizationDeltaAttached))
                // Structural: the bundle type cannot carry raw output, only digests.
                put(EvidenceRequirement.OUTPUT_PRIVACY, FactStatus.PRESENT)
                put(EvidenceRequirement.SIGNATURE, presence(signaturePlanned))
                put(EvidenceRequirement.HARDWARE_ATTESTATION, presence(hardwareAttestationPlanned))
                put(EvidenceRequirement.CAPABILITY_GAPS_RECORDED, FactStatus.PRESENT)
            }
            return EvidenceFacts(facts)
        }

        private fun presence(flag: Boolean) = if (flag) FactStatus.PRESENT else FactStatus.ABSENT

        const val MIN_ITERATIONS_FOR_ENVELOPE = 10
    }
}
