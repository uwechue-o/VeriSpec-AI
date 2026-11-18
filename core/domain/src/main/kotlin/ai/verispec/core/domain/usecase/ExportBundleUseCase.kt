package ai.verispec.core.domain.usecase

import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.ExportFormat
import ai.verispec.core.domain.model.ExportedArtifact
import ai.verispec.core.domain.port.EvidenceRepository

/** JSON + PDF rendering of a stored bundle. */
class ExportBundleUseCase(private val repository: EvidenceRepository) {
    suspend operator fun invoke(id: BundleId, format: ExportFormat): ExportedArtifact =
        repository.export(id, format)
}
