package ai.verispec.core.evidence

import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.UnsignedBundle
import ai.verispec.core.domain.port.Canonicalizer
import ai.verispec.core.evidence.canonical.CanonicalJson
import ai.verispec.core.evidence.wire.ManifestContentDto
import ai.verispec.core.evidence.wire.UnsignedBundleDto
import ai.verispec.core.evidence.wire.toContentDto
import ai.verispec.core.evidence.wire.toDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The one place that turns domain objects into the bytes that get hashed and
 * signed. Uses the wire DTOs, then the canonical writer. Property-tested.
 */
object EvidenceCanonicalizer : Canonicalizer {

    /** Strict Json: every field emitted, nulls explicit, no lenient parsing. */
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
        classDiscriminator = "type"
    }

    override fun manifestBytes(manifest: RunManifest): ByteArray =
        CanonicalJson.encode(json.encodeToJsonElement(ManifestContentDto.serializer(), manifest.toContentDto()))

    override fun bundleBytes(bundle: UnsignedBundle): ByteArray =
        CanonicalJson.encode(json.encodeToJsonElement(UnsignedBundleDto.serializer(), bundle.toDto()))
}
