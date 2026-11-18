package ai.verispec.data.runtime.api

import ai.verispec.core.domain.model.BackendCapabilities
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.port.InferenceBackend
import kotlinx.coroutines.CancellationException

/**
 * Capability discovery at startup, not compile-time assumption. Every adapter
 * compiled into the app registers here; the registry asks each one what it can
 * actually do on this device and records the answer, including failures.
 */
class BackendRegistry(private val backends: Map<RuntimeId, InferenceBackend>) {

    data class Discovery(
        val capabilities: Map<RuntimeId, BackendCapabilities>,
    ) {
        val available: List<BackendCapabilities> get() = capabilities.values.filter { it.available }.sortedBy { it.runtime.ordinal }
        val gaps: List<BackendCapabilities> get() = capabilities.values.filter { !it.available }.sortedBy { it.runtime.ordinal }

        /** Human-readable list of what this device cannot do; goes into provenance notes. */
        fun gapNotes(): List<String> = gaps.map { "Backend ${it.runtime} unavailable: ${it.unavailableReason ?: "unknown"}" }
    }

    fun backend(id: RuntimeId): InferenceBackend? = backends[id]

    val registered: Set<RuntimeId> get() = backends.keys

    suspend fun discover(): Discovery {
        val caps = LinkedHashMap<RuntimeId, BackendCapabilities>()
        for (id in RuntimeId.entries) {
            val backend = backends[id]
            caps[id] = if (backend == null) {
                BackendCapabilities.unavailable(id, "no adapter compiled into this build")
            } else {
                try {
                    backend.capabilities()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // A hostile runtime must degrade to a recorded gap, never a crash.
                    BackendCapabilities.unavailable(id, "capability probe failed: ${t::class.simpleName}: ${t.message}")
                }
            }
        }
        return Discovery(caps)
    }
}
