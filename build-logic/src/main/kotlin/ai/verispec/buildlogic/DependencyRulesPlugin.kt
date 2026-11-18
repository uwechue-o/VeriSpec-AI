package ai.verispec.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Mechanical enforcement of the Clean Architecture dependency rule
 * (GUIDE.md §2): arrows point inward only.
 *
 * ```
 *   core/…   -> core/…
 *   data/…   -> core/…, data/runtime-api
 *   app/…    -> core/…, data/…, app/…
 *   tools/…  -> core/…, tools/…
 * ```
 *
 * `tools/…` is JVM-only and may never see an Android module; `core/…` may never
 * see anything outside `core`. Violations fail the build at configuration time,
 * so there is no way to merge a project that breaks the layering.
 */
class DependencyRulesPlugin : Plugin<Project> {

    private enum class Layer(val prefix: String) {
        CORE(":core:"), DATA(":data:"), APP(":app:"), TOOLS(":tools:");

        companion object {
            fun of(path: String): Layer? = values().firstOrNull { path.startsWith(it.prefix) }
        }
    }

    private val checkedConfigurations = setOf(
        "api", "implementation", "compileOnly", "runtimeOnly",
        "debugImplementation", "releaseImplementation", "ksp",
    )

    override fun apply(target: Project) {
        require(target == target.rootProject) {
            "verispec.dependency.rules must be applied to the root project"
        }

        target.subprojects.forEach { sub ->
            sub.afterEvaluate { verify(sub) }
        }

        target.tasks.register("verifyDependencyRules") {
            group = "verification"
            description = "Fails if any module depends on a module in an outer layer."
            doLast {
                target.subprojects.forEach { verify(it) }
                logger.lifecycle("Dependency rules verified for ${target.subprojects.size} modules.")
            }
        }
    }

    private fun verify(project: Project) {
        val fromLayer = Layer.of(project.path) ?: return
        val violations = mutableListOf<String>()

        project.configurations
            .filter { it.name in checkedConfigurations }
            .forEach { configuration ->
                configuration.dependencies
                    .filterIsInstance<ProjectDependency>()
                    .forEach { dependency ->
                        val toPath = dependency.path
                        val toLayer = Layer.of(toPath) ?: return@forEach
                        if (!isAllowed(fromLayer, toLayer, toPath)) {
                            violations += "${project.path} (${configuration.name}) -> $toPath"
                        }
                    }
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Dependency rule violation — arrows must point inward only:\n" +
                    violations.joinToString("\n") { "  $it" },
            )
        }
    }

    private fun isAllowed(from: Layer, to: Layer, toPath: String): Boolean = when (from) {
        Layer.CORE -> to == Layer.CORE
        Layer.DATA -> to == Layer.CORE || toPath == ":data:runtime-api"
        Layer.APP -> to != Layer.TOOLS
        Layer.TOOLS -> to == Layer.CORE || to == Layer.TOOLS
    }
}
