// app/ui — the on-device operator console. Compose + MVVM. Composition root for Hilt.
import java.nio.ByteBuffer
import java.nio.ByteOrder

plugins {
    alias(libs.plugins.verispec.android.application)
    alias(libs.plugins.verispec.android.compose)
    alias(libs.plugins.verispec.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ai.verispec.app"

    defaultConfig {
        applicationId = "ai.verispec.app"
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "TOOL_VERSION", "\"0.1.0\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

// Phones with 16 KB memory pages (Android 15+) warn about, and will eventually refuse,
// 64-bit native libraries whose LOAD segments are aligned to less than 16 KB. A
// dependency bump can bring one back silently, so fail the build rather than the phone.
androidComponents.onVariants { variant ->
    val cap = variant.name.replaceFirstChar { it.uppercase() }
    val check = tasks.register("check${cap}NativePageAlignment") {
        group = "verification"
        description = "Fails if a packaged 64-bit native library is not 16 KB page-aligned."
        val nativeLibs = files(provider { tasks.named("merge${cap}NativeLibs").map { it.outputs.files } })
            .asFileTree.matching { include("**/*.so") }
        inputs.files(nativeLibs)
        doLast {
            val bad = nativeLibs.files.mapNotNull { so ->
                val align = minLoadAlignment(so.readBytes()) ?: return@mapNotNull null
                if (align < 16_384) "${so.parentFile.name}/${so.name}: p_align $align" else null
            }.sorted()
            check(bad.isEmpty()) { "Native libraries not 16 KB-aligned:\n  " + bad.joinToString("\n  ") }
        }
    }
    tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(check) }
}

/** Smallest p_align over the PT_LOAD segments of a 64-bit little-endian ELF, or null for anything else. */
fun minLoadAlignment(elf: ByteArray): Long? {
    if (elf.size < 64 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() || elf[4] != 2.toByte()) return null
    val buf = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
    val phoff = buf.getLong(0x20).toInt()
    val phentsize = buf.getShort(0x36).toInt()
    val phnum = buf.getShort(0x38).toInt()
    var min: Long? = null
    for (i in 0 until phnum) {
        val ph = phoff + i * phentsize
        if (buf.getInt(ph) == 1) min = minOf(min ?: Long.MAX_VALUE, buf.getLong(ph + 0x30))
    }
    return min
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:evidence"))
    implementation(project(":core:clauses"))
    implementation(project(":data:runtime-api"))
    implementation(project(":data:runtime-litert"))
    implementation(project(":data:runtime-llamacpp"))
    implementation(project(":data:runtime-executorch"))
    implementation(project(":data:runtime-mediapipe"))
    implementation(project(":data:telemetry"))
    implementation(project(":data:attestation"))
    implementation(project(":data:persistence"))
    implementation(project(":app:runner"))

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)
}
