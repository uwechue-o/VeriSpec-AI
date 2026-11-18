// core/domain — pure Kotlin/JVM. Zero Android, zero framework, zero annotations.
// Allowed dependencies: Kotlin stdlib, kotlinx-coroutines, kotlinx-datetime.
plugins {
    alias(libs.plugins.verispec.kotlin.jvm)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)
}
