plugins {
    `kotlin-dsl`
}

group = "ai.verispec.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.plugin.android.gradle)
    compileOnly(libs.plugin.kotlin.gradle)
    compileOnly(libs.plugin.kotlin.compose)
    compileOnly(libs.plugin.ksp.gradle)
    compileOnly(libs.plugin.hilt.gradle)
}

gradlePlugin {
    plugins {
        register("kotlinJvm") {
            id = "verispec.kotlin.jvm"
            implementationClass = "ai.verispec.buildlogic.KotlinJvmConventionPlugin"
        }
        register("androidLibrary") {
            id = "verispec.android.library"
            implementationClass = "ai.verispec.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "verispec.android.application"
            implementationClass = "ai.verispec.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidCompose") {
            id = "verispec.android.compose"
            implementationClass = "ai.verispec.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "verispec.android.hilt"
            implementationClass = "ai.verispec.buildlogic.AndroidHiltConventionPlugin"
        }
        register("dependencyRules") {
            id = "verispec.dependency.rules"
            implementationClass = "ai.verispec.buildlogic.DependencyRulesPlugin"
        }
    }
}
