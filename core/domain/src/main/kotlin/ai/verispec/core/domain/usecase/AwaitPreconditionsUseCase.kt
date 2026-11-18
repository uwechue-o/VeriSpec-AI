package ai.verispec.core.domain.usecase

import ai.verispec.core.domain.model.PreconditionPolicy
import ai.verispec.core.domain.model.PreconditionState
import ai.verispec.core.domain.port.Clock
import ai.verispec.core.domain.port.DeviceConditionSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Polls device conditions until [PreconditionPolicy] has held for its whole
 * stabilization window (Satisfied) or the maximum wait elapses (TimedOut). The
 * flow completes on the terminal state.
 *
 * The window restarts whenever any condition fails again: holding, briefly
 * charging, then holding again is not a stable device, and treating it as one
 * would put a warm start into a run that claims to be cold.
 */
class AwaitPreconditionsUseCase(
    private val conditions: DeviceConditionSource,
    private val clock: Clock,
    private val pollIntervalMillis: Long = DEFAULT_POLL_MILLIS,
) {
    operator fun invoke(policy: PreconditionPolicy): Flow<PreconditionState> = flow {
        val startNanos = clock.monotonicNanos()
        var windowStartNanos: Long? = null
        var held = 0L
        while (true) {
            val reading = conditions.read()
            val now = clock.monotonicNanos()
            val waited = (now - startNanos) / 1_000_000
            val unmet = policy.evaluate(reading)

            if (unmet.isEmpty()) {
                val windowStart = windowStartNanos ?: now.also { windowStartNanos = it }
                held = (now - windowStart) / 1_000_000
                if (held >= policy.stabilizationWindowMillis) {
                    emit(PreconditionState.Satisfied(waited, reading))
                    return@flow
                }
                emit(PreconditionState.Waiting(emptyList(), held, waited, reading))
            } else {
                windowStartNanos = null
                held = 0
                emit(PreconditionState.Waiting(unmet, 0, waited, reading))
            }

            if (waited >= policy.maxWaitMillis) {
                emit(PreconditionState.TimedOut(waited, unmet, held, reading))
                return@flow
            }
            delay(pollIntervalMillis)
        }
    }

    companion object {
        const val DEFAULT_POLL_MILLIS = 2_000L
    }
}
