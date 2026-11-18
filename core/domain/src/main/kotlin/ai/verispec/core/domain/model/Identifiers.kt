package ai.verispec.core.domain.model

/**
 * Identity of a run. The value is the SHA-256 of the manifest's canonical bytes
 * (excluding this field), so the hash IS the run identity: two manifests with
 * identical content are the same run by construction.
 */
@JvmInline
value class ManifestId(val value: String) {
    init { require(Sha256.isHex(value)) { "ManifestId must be 64 lowercase hex chars" } }
    val short: String get() = value.take(12)
    override fun toString(): String = value
}

/** Identity of a bundle: SHA-256 of the canonical unsigned bundle bytes. */
@JvmInline
value class BundleId(val value: String) {
    init { require(Sha256.isHex(value)) { "BundleId must be 64 lowercase hex chars" } }
    val short: String get() = value.take(12)
    override fun toString(): String = value
}

/** Lowercase hex SHA-256 digest. */
@JvmInline
value class Sha256(val hex: String) {
    init { require(isHex(hex)) { "Sha256 must be 64 lowercase hex chars, was '$hex'" } }
    val short: String get() = hex.take(12)
    override fun toString(): String = hex

    companion object {
        private val HEX = Regex("^[0-9a-f]{64}$")
        fun isHex(candidate: String): Boolean = HEX.matches(candidate)
        fun fromBytes(bytes: ByteArray): Sha256 {
            require(bytes.size == 32) { "SHA-256 digest must be 32 bytes" }
            return Sha256(bytes.joinToString("") { "%02x".format(it) })
        }
    }
}

/** Semantic version. Ordering compares major.minor.patch only. */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemVer> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "SemVer components must be non-negative" }
    }

    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString(): String =
        "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?(?:\+[0-9A-Za-z.\-]+)?$""")

        fun parse(text: String): SemVer {
            val m = PATTERN.matchEntire(text.trim())
                ?: throw IllegalArgumentException("Not a semantic version: '$text'")
            return SemVer(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                patch = m.groupValues[3].ifEmpty { "0" }.toInt(),
                preRelease = m.groupValues[4].ifEmpty { null },
            )
        }

        fun parseOrNull(text: String?): SemVer? =
            text?.let { runCatching { parse(it) }.getOrNull() }
    }
}

/** Version of a regulatory clause catalog, e.g. "2026.1". */
@JvmInline
value class CatalogVersion(val value: String) {
    init { require(value.isNotBlank()) { "CatalogVersion must not be blank" } }
    override fun toString(): String = value
}

/** Stable identifier of a clause within a framework, e.g. "EU-AIA-ART15-1". */
@JvmInline
value class ClauseId(val value: String) {
    init { require(value.isNotBlank()) { "ClauseId must not be blank" } }
    override fun toString(): String = value
}
