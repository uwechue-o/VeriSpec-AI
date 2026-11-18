package ai.verispec.core.clauses

import ai.verispec.core.domain.model.CatalogVersion
import ai.verispec.core.domain.model.CoverageStatus
import ai.verispec.core.domain.model.EvidenceFacts
import ai.verispec.core.domain.model.EvidenceRequirement
import ai.verispec.core.domain.model.FactStatus
import ai.verispec.core.domain.model.RegulatoryFramework
import ai.verispec.core.domain.usecase.MapClausesUseCase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class YamlClauseCatalogTest : StringSpec({

    val catalog = YamlClauseCatalog()

    "latest catalog loads every framework with unique ids" {
        val clauses = catalog.catalog(catalog.latestVersion())
        clauses.size shouldBeGreaterThan 20
        clauses.map { it.framework }.toSet() shouldContainAll RegulatoryFramework.entries
        clauses.map { it.id }.toSet().size shouldBe clauses.size
        clauses.forEach { it.evidence.shouldNotBeEmpty() }
    }

    "every evidence requirement is referenced by at least one clause" {
        val referenced = catalog.catalog(CatalogVersion("2026.1")).flatMap { it.evidence }.toSet()
        referenced shouldContainAll EvidenceRequirement.entries
    }

    "mapper names gaps rather than hiding them" {
        val facts = EvidenceFacts(
            EvidenceRequirement.entries.associateWith { FactStatus.PRESENT } +
                mapOf(
                    EvidenceRequirement.NUMERICAL_DIVERGENCE to FactStatus.ABSENT,
                    EvidenceRequirement.HARDWARE_ATTESTATION to FactStatus.ABSENT,
                ),
        )
        val coverage = MapClausesUseCase(catalog)(facts)
        val art15 = coverage.first { it.clauseId.value == "EU-AIA-ART15-1" }
        art15.status shouldBe CoverageStatus.PARTIAL
        art15.missing shouldBe setOf(EvidenceRequirement.NUMERICAL_DIVERGENCE)
        art15.gapDescription.shouldNotBeNull()
        coverage.count { it.status == CoverageStatus.SATISFIED } shouldBeGreaterThan 0
    }

    "a bundle with nothing but a manifest is mostly uncovered" {
        val facts = EvidenceFacts(mapOf(EvidenceRequirement.REPRODUCIBLE_MANIFEST to FactStatus.PRESENT))
        val coverage = MapClausesUseCase(catalog)(facts, setOf(RegulatoryFramework.EU_AI_ACT))
        coverage.count { it.status == CoverageStatus.NOT_COVERED } shouldBeGreaterThan coverage.count { it.status == CoverageStatus.SATISFIED }
    }
})
