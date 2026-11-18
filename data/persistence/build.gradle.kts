// data/persistence — Room (runs, iterations, bundle index), DataStore settings,
// file-backed bundle store. Bundles are files referenced by row, never blobs.
plugins {
    alias(libs.plugins.verispec.android.library)
    alias(libs.plugins.verispec.android.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.room)
}

android {
    namespace = "ai.verispec.data.persistence"
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:evidence"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)
}
