package ai.verispec.data.attestation

import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.model.TrustedApps
import ai.verispec.core.evidence.Hashing
import android.content.Context
import android.content.pm.PackageManager

/**
 * The running app's own identity, in the form key attestation records it: its
 * package and the SHA-256 of each certificate that signed its APK. A build made
 * with another key accepts its own bundles on device; elsewhere that key has to
 * be trusted explicitly.
 */
object OwnSigner {
    fun of(context: Context): TrustedApps {
        val signers = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
                .orEmpty()
                .map { Hashing.sha256(it.toByteArray()) }
        }.getOrDefault(emptyList())
        return TrustedApps(packageNames = setOf(context.packageName), signerDigests = signers.toSet<Sha256>())
    }
}
