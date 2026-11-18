package ai.verispec.core.domain.usecase

import ai.verispec.core.domain.model.CatalogVersion
import ai.verispec.core.domain.model.ClauseCoverage
import ai.verispec.core.domain.model.CoverageStatus
import ai.verispec.core.domain.model.EvidenceFacts
import ai.verispec.core.domain.model.FactStatus
import ai.verispec.core.domain.model.RegulatoryFramework
import ai.verispec.core.domain.port.ClauseCatalog

/**
 * Bundle facts × clause catalog → coverage, with every gap named. The catalog is
 * data (versioned YAML), so a regulatory change is a resource update, not a release.
 */
class MapClausesUseCase(private val catalog: ClauseCatalog) {

    suspend operator fun invoke(
        facts: EvidenceFacts,
        frameworks: Set<RegulatoryFramework> = RegulatoryFramework.entries.toSet(),
        version: CatalogVersion? = null,
    ): List<ClauseCoverage> {
        val resolved = version ?: catalog.latestVersion()
        return catalog.catalog(resolved)
            .filter { it.framework in frameworks }
            .map { clause ->
                val satisfied = clause.evidence.filter { facts.status(it) == FactStatus.PRESENT }.toSet()
                val partial = clause.evidence.filter { facts.status(it) == FactStatus.PARTIAL }.toSet()
                val missing = clause.evidence - satisfied
                val status = when {
                    missing.isEmpty() -> CoverageStatus.SATISFIED
                    satisfied.isEmpty() && partial.isEmpty() -> CoverageStatus.NOT_COVERED
                    else -> CoverageStatus.PARTIAL
                }
                ClauseCoverage(
                    clauseId = clause.id,
                    framework = clause.framework,
                    reference = clause.reference,
                    title = clause.title,
                    status = status,
                    satisfied = satisfied,
                    missing = missing,
                    gapDescription = describeGap(missing, partial),
                )
            }
            .sortedWith(compareBy({ it.framework.ordinal }, { it.clauseId.value }))
    }

    private fun describeGap(
        missing: Set<ai.verispec.core.domain.model.EvidenceRequirement>,
        partial: Set<ai.verispec.core.domain.model.EvidenceRequirement>,
    ): String? {
        if (missing.isEmpty()) return null
        val absent = (missing - partial).sorted()
        val weak = partial.sorted()
        return buildString {
            if (absent.isNotEmpty()) append("Not evidenced: ").append(absent.joinToString { it.name.lowercase().replace('_', ' ') })
            if (weak.isNotEmpty()) {
                if (isNotEmpty()) append(". ")
                append("Only partially evidenced: ").append(weak.joinToString { it.name.lowercase().replace('_', ' ') })
            }
            append('.')
        }
    }
}
