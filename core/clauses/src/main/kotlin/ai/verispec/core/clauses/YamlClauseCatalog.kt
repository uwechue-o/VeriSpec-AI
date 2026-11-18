package ai.verispec.core.clauses

import ai.verispec.core.domain.model.CatalogVersion
import ai.verispec.core.domain.model.ClauseDefinition
import ai.verispec.core.domain.model.ClauseId
import ai.verispec.core.domain.model.EvidenceRequirement
import ai.verispec.core.domain.model.RegulatoryFramework
import ai.verispec.core.domain.port.ClauseCatalog
import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable

@Serializable
internal data class ClauseEntryYaml(
    val id: String,
    val reference: String,
    val title: String,
    val requirement: String,
    val evidence: List<String>,
    val rationale: String,
)

@Serializable
internal data class ClauseCatalogYaml(
    val catalogVersion: String,
    val framework: String,
    val source: String,
    val clauses: List<ClauseEntryYaml>,
)

/**
 * Clause mappings are data, loaded from versioned YAML resources, never
 * hardcoded. Each catalog version is a directory under `/clauses/<version>/`
 * containing one file per framework. Adding a version never modifies an old one.
 */
class YamlClauseCatalog(
    private val resourceLoader: (String) -> java.io.InputStream? = { path -> YamlClauseCatalog::class.java.getResourceAsStream(path) },
) : ClauseCatalog {

    private val yaml = Yaml.default
    private val cache = HashMap<CatalogVersion, List<ClauseDefinition>>()

    override suspend fun latestVersion(): CatalogVersion = CatalogVersion(LATEST)

    override suspend fun catalog(version: CatalogVersion): List<ClauseDefinition> =
        synchronized(cache) {
            cache.getOrPut(version) { load(version) }
        }

    private fun load(version: CatalogVersion): List<ClauseDefinition> {
        require(version.value in KNOWN_VERSIONS) { "Unknown clause catalog version '${version.value}'; known: $KNOWN_VERSIONS" }
        return FILES.flatMap { file ->
            val path = "/clauses/${version.value}/$file"
            val stream = resourceLoader(path) ?: error("Clause catalog resource missing: $path")
            val parsed = stream.bufferedReader().use { yaml.decodeFromString(ClauseCatalogYaml.serializer(), it.readText()) }
            require(parsed.catalogVersion == version.value) {
                "Catalog file $path declares version ${parsed.catalogVersion}, expected ${version.value}"
            }
            val framework = RegulatoryFramework.valueOf(parsed.framework)
            parsed.clauses.map { entry ->
                ClauseDefinition(
                    id = ClauseId(entry.id),
                    framework = framework,
                    reference = entry.reference,
                    title = entry.title,
                    requirement = entry.requirement,
                    evidence = entry.evidence.map { EvidenceRequirement.valueOf(it) }.toSet(),
                    rationale = entry.rationale,
                )
            }
        }.also { all ->
            val dupes = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(dupes.isEmpty()) { "Duplicate clause ids in catalog ${version.value}: $dupes" }
        }
    }

    companion object {
        const val LATEST = "2026.1"
        val KNOWN_VERSIONS = listOf("2026.1")
        private val FILES = listOf("eu-ai-act.yaml", "iso-iec-42001.yaml", "iso-pas-8800.yaml", "fda-pccp.yaml")
    }
}
