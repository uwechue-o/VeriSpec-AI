// core/clauses — versioned regulatory clause catalog (YAML resources) + mapper.
plugins {
    alias(libs.plugins.verispec.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
}
