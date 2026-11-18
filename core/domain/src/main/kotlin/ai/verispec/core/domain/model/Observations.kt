package ai.verispec.core.domain.model

/**
 * Mirrors the Android thermal status ladder. Ordinal order is severity order,
 * except [UNKNOWN] which means the platform did not report.
 */
enum class ThermalStatus {
    NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN;

    /**
     * Platform semantics: LIGHT is "throttling where UX is not impacted"; from
     * MODERATE upward the device is materially reducing performance. We record
     * an iteration as throttled from MODERATE ([IterationRecord.wasThrottled]).
     */
    val isThrottling: Boolean get() = this != UNKNOWN && ordinal >= MODERATE.ordinal

    /** Any throttling at all, from LIGHT. The envelope dates the first throttle from here. */
    val isThrottleOnset: Boolean get() = this != UNKNOWN && ordinal >= LIGHT.ordinal

    companion object {
        fun maxOf(a: ThermalStatus, b: ThermalStatus): ThermalStatus = when {
            a == UNKNOWN -> b
            b == UNKNOWN -> a
            else -> if (a.ordinal >= b.ordinal) a else b
        }
    }
}

/** One thermal observation. [elapsedMillis] is relative to run start, never wall-clock. */
data class ThermalSample(
    val elapsedMillis: Long,
    val status: ThermalStatus,
    /** 0.0 = cool, 1.0 = severe throttling threshold (PowerManager.getThermalHeadroom). */
    val headroom: Float?,
    val zoneTemperaturesCelsius: Map<String, Float> = emptyMap(),
)

data class PowerSample(
    val elapsedMillis: Long,
    val currentMicroAmps: Long?,
    val voltageMilliVolts: Int?,
    val batteryLevelPercent: Int?,
    val isCharging: Boolean,
    /** Tenths of a degree Celsius, from the battery broadcast; null when absent. */
    val batteryTemperatureDeciC: Int? = null,
    /** Whether the screen was on. The panel is a heat source, so a policy that changed mid-run shows here. */
    val screenOn: Boolean? = null,
) {
    /**
     * This sample alone, taking the current as microamperes as Android documents.
     * Some vendors report milliamperes; use BatteryCurrent for anything run-level.
     */
    val powerMilliWatts: Double?
        get() = if (currentMicroAmps != null && voltageMilliVolts != null) {
            kotlin.math.abs(currentMicroAmps.toDouble()) / 1_000.0 * voltageMilliVolts.toDouble() / 1_000.0
        } else {
            null
        }
}

data class MemorySnapshot(
    val pssKb: Long,
    val nativeHeapKb: Long,
    val rssKb: Long?,
    val availableSystemKb: Long,
)

data class MemoryEnvelope(
    val peakPssKb: Long,
    val peakNativeHeapKb: Long,
    val peakRssKb: Long?,
    val availableAtStartKb: Long,
    val availableAtEndKb: Long,
) {
    companion object {
        fun from(snapshots: List<MemorySnapshot>): MemoryEnvelope {
            require(snapshots.isNotEmpty()) { "At least one memory snapshot is required" }
            return MemoryEnvelope(
                peakPssKb = snapshots.maxOf { it.pssKb },
                peakNativeHeapKb = snapshots.maxOf { it.nativeHeapKb },
                peakRssKb = snapshots.mapNotNull { it.rssKb }.maxOrNull(),
                availableAtStartKb = snapshots.first().availableSystemKb,
                availableAtEndKb = snapshots.last().availableSystemKb,
            )
        }
    }
}

data class IterationRecord(
    val index: Int,
    /** Relative to run start (monotonic clock), not wall-clock. */
    val elapsedMillis: Long,
    val timeToFirstTokenMicros: Long,
    val totalMicros: Long,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val decodeTokensPerSecond: Double,
    val prefillTokensPerSecond: Double,
    val thermalStatusAtStart: ThermalStatus,
    val thermalStatusAtEnd: ThermalStatus,
    val wasThrottled: Boolean,
    /** Clocks read right after the iteration, outside its timed region; null when nothing was readable. */
    val clocks: ClockReading? = null,
)

/** CPU and GPU clock frequencies at one instant. */
data class ClockReading(
    /**
     * Per core, in core order. Null unless every core was readable: a partial set
     * would be mistaken for the whole, and an offline core reads as unreadable.
     */
    val cpuKhz: List<Long>?,
    val gpuKhz: Long?,
)

/**
 * Which hardware signals this device let the app read, discovered once at run
 * start. What was unreadable is recorded as a gap, never treated as an error.
 */
data class TelemetryInventory(
    /** Human-readable names, e.g. "cpu clock (8 cores)" or "thermal zone: skin". */
    val readable: List<String>,
    /** What was looked for and could not be read, with where it was looked for. */
    val unavailable: List<String>,
)

/**
 * A digest, not raw output. Bundles must not carry model outputs: they may
 * contain input-derived PII. We store the hash plus an optional fixed-size
 * numeric projection sufficient for divergence math.
 */
class OutputDigest(
    val iterationIndex: Int,
    val sha256: Sha256,
    val logitProjection: FloatArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is OutputDigest &&
            iterationIndex == other.iterationIndex &&
            sha256 == other.sha256 &&
            (if (logitProjection == null) other.logitProjection == null
            else other.logitProjection != null && logitProjection.contentEquals(other.logitProjection))

    override fun hashCode(): Int {
        var h = iterationIndex
        h = 31 * h + sha256.hashCode()
        h = 31 * h + (logitProjection?.contentHashCode() ?: 0)
        return h
    }

    override fun toString(): String =
        "OutputDigest(iteration=$iterationIndex, sha256=${sha256.short}…, projection=${logitProjection?.size})"
}

data class Observations(
    val iterations: List<IterationRecord>,
    val thermalTimeline: List<ThermalSample>,
    val powerTimeline: List<PowerSample>,
    val memoryPeaks: MemoryEnvelope,
    val outputDigests: List<OutputDigest>,
    /** Set when the run stopped before schedule.iterations because of a sustained-duration cap. */
    val stoppedEarlyReason: String? = null,
    /** Wall-clock start recorded once for provenance; every other timestamp is relative. */
    val runStartedAtEpochMillis: Long,
    val runDurationMillis: Long,
    /** What the precondition gate found; null when the manifest had no gate. */
    val preconditions: PreconditionOutcome? = null,
    /** Readable hardware signals; null in bundles from before 1.2.0. */
    val telemetry: TelemetryInventory? = null,
)
