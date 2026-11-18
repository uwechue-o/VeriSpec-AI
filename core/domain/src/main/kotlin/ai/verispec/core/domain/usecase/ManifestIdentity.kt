package ai.verispec.core.domain.usecase

import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.port.Canonicalizer
import ai.verispec.core.domain.port.Hasher

/** Assigns the content hash as the manifest identity. */
class ManifestIdentity(
    private val canonicalizer: Canonicalizer,
    private val hasher: Hasher,
) {
    /** Placeholder id used while a manifest is being composed, before hashing. */
    val placeholder: ManifestId = ManifestId("0".repeat(64))

    fun compute(manifest: RunManifest): ManifestId =
        ManifestId(hasher.sha256(canonicalizer.manifestBytes(manifest)).hex)

    fun withComputedId(manifest: RunManifest): RunManifest = manifest.copy(manifestId = compute(manifest))
}
