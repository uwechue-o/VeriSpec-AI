package ai.verispec.data.attestation

import ai.verispec.core.domain.port.Clock
import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/** Monotonic time from elapsedRealtimeNanos (survives deep sleep); wall time recorded once per run. */
@Singleton
class AndroidClock @Inject constructor() : Clock {
    override fun epochMillis(): Long = System.currentTimeMillis()
    override fun monotonicNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
