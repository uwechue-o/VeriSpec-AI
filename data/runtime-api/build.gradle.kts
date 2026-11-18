// data/runtime-api — backend registry, capability discovery and shared adapter
// helpers. JVM-only so tools/* could reuse it; the port itself lives in core/domain.
plugins {
    alias(libs.plugins.verispec.kotlin.jvm)
}

dependencies {
    api(project(":core:domain"))
    api(libs.dagger)
}
