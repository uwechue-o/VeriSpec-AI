package ai.verispec.tools.cli

import ai.verispec.core.clauses.YamlClauseCatalog
import ai.verispec.core.domain.model.EvidenceFacts
import ai.verispec.core.domain.usecase.MapClausesUseCase
import ai.verispec.core.evidence.BundleCodec
import ai.verispec.core.evidence.EvidenceCanonicalizer
import ai.verispec.core.evidence.Hashing
import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.UnsignedBundle
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Writes a pair of realistic, really-signed sample bundles to disk so the CLI
 * can be exercised by hand:
 *
 *   ./gradlew :tools:cli:test --tests '*SampleBundleWriter*' -Dverispec.samples=build/samples
 *
 * Skipped unless that system property is set, so it never runs in a normal build.
 */
object SampleBundleWriter {

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "build/samples").also { it.mkdirs() }
        val catalog = YamlClauseCatalog()

        listOf(
            Triple("pixel8pro", "Tensor G3", null),
            Triple("galaxys24", "Snapdragon 8 Gen 3", 7),
        ).forEach { (deviceId, soc, drift) ->
            // Map clauses first, then sign, so the signature covers the final
            // content. Doing it the other way round produces an invalid bundle.
            val draft = SignedFixture.build(deviceId, soc, driftFromIteration = drift).bundle
            val facts = EvidenceFacts.derive(
                manifest = draft.manifest,
                device = draft.device,
                provenance = draft.provenance,
                observations = draft.observations,
                signaturePlanned = true,
                hardwareAttestationPlanned = true,
                divergenceReportAttached = drift != null,
                quantizationDeltaAttached = false,
            )
            val coverage = runBlocking { MapClausesUseCase(catalog)(facts) }
            val bundle = SignedFixture.build(deviceId, soc, driftFromIteration = drift, clauseCoverage = coverage).bundle

            val out = File(dir, "$deviceId.json")
            out.writeText(BundleCodec.encodePretty(bundle))
            println("wrote ${out.absolutePath} (${out.length()} bytes, ${coverage.size} clauses)")
        }
    }
}
