package ai.verispec.core.evidence

import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.evidence.canonical.CanonicalJson
import ai.verispec.core.evidence.wire.EvidenceBundleDto
import ai.verispec.core.evidence.wire.ManifestDto
import ai.verispec.core.evidence.wire.toDomain
import ai.verispec.core.evidence.wire.toDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Reads and writes signed bundles and standalone manifests as JSON files.
 * Output is always canonical (sorted keys, no whitespace) so a file's bytes are
 * a function of its content alone; a pretty variant exists for humans.
 */
object BundleCodec {

    private val json: Json = EvidenceCanonicalizer.json

    private val pretty: Json = Json(from = json) { prettyPrint = true; prettyPrintIndent = "  " }

    fun encode(bundle: EvidenceBundle): ByteArray =
        CanonicalJson.encode(json.encodeToJsonElement(EvidenceBundleDto.serializer(), bundle.toDto()))

    fun encodePretty(bundle: EvidenceBundle): String =
        pretty.encodeToString(EvidenceBundleDto.serializer(), bundle.toDto())

    fun decode(bytes: ByteArray): EvidenceBundle = decode(bytes.toString(Charsets.UTF_8))

    fun decode(text: String): EvidenceBundle =
        json.decodeFromString(EvidenceBundleDto.serializer(), text).toDomain()

    fun encodeManifest(manifest: RunManifest): ByteArray =
        CanonicalJson.encode(json.encodeToJsonElement(ManifestDto.serializer(), manifest.toDto()))

    fun encodeManifestPretty(manifest: RunManifest): String =
        pretty.encodeToString(ManifestDto.serializer(), manifest.toDto())

    fun decodeManifest(text: String): RunManifest =
        json.decodeFromString(ManifestDto.serializer(), text).toDomain()

    /** Suggested file name: content-addressed, so re-exports never collide. */
    fun fileNameFor(bundle: EvidenceBundle): String = "verispec-bundle-${bundle.bundleId.short}.json"
}
