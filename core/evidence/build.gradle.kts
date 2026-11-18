// core/evidence — bundle wire model, canonical serialization, hashing, JSON Schema.
plugins {
    alias(libs.plugins.verispec.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.serialization.json)
    implementation(libs.okio)

    testImplementation(libs.json.schema.validator)
}
