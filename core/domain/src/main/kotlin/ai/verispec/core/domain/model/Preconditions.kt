package ai.verispec.core.domain.model

import kotlin.math.floor

/*
 * The precondition gate: the device state a run must start from. A warm start
 * measures something different from a cold one, and charging, a low battery or
 * a lit screen are heat or throttling sources of their own. Ported from
 * ThermalBench's protocol; the policy is part of the hashed manifest, so two
 * runs gated differently are different runs.
 */

/**
 * The screen state a run requires. It must be fixed, never incidental: the panel
 * is a heat source, and some SoCs clock the GPU down with the screen off.
 */
enum class ScreenPolicy {
    OFF,

    /** On, at whatever brightness the operator left it. */
    ON,

    /** On, held on at minimum brightness by the app for the whole run. The reproducible way to measure screen-on. */
    ON_DIMMED,
}

data class PreconditionPolicy(
    /** The device must be at or below this status. Typically NONE. */
    val requiredThermalStatus: ThermalStatus = ThermalStatus.NONE,
    /**
     * Minimum margin to throttling, 1 - PowerManager.getThermalHeadroom, in 0..1.
     * Null skips the check. Devices that do not report headroom are not blocked.
     */
    val minHeadroomMargin: Float? = 0.5f,
    /** Charging is a heat source. */
    val requireUnplugged: Boolean = true,
    /** Low battery triggers its own throttling. */
    val minBatteryPercent: Int = 50,
    val screenPolicy: ScreenPolicy = ScreenPolicy.OFF,
    /** How long every condition must hold, uninterrupted, before the run starts. */
    val stabilizationWindowMillis: Long = 30_000,
    val maxWaitMillis: Long = 15 * 60_000,
    /** On timeout, run anyway and record the gate as unmet, instead of failing the run. */
    val proceedIfUnmet: Boolean = false,
) {
    init {
        require(requiredThermalStatus != ThermalStatus.UNKNOWN) { "requiredThermalStatus must be a real status" }
        minHeadroomMargin?.let { require(it in 0f..1f) { "minHeadroomMargin must be within 0..1" } }
        require(minBatteryPercent in 0..100) { "minBatteryPercent must be within 0..100" }
        require(stabilizationWindowMillis >= 0) { "stabilizationWindowMillis must be non-negative" }
        require(maxWaitMillis > 0) { "maxWaitMillis must be positive" }
    }

    /** Pure, so it is testable without a device. Empty means every condition holds. */
    fun evaluate(c: DeviceConditions): List<UnmetCondition> = buildList {
        // An unknown status is never read as cold.
        if (c.thermalStatus == ThermalStatus.UNKNOWN || c.thermalStatus.ordinal > requiredThermalStatus.ordinal) {
            add(UnmetCondition.ThermalStatusTooHigh(c.thermalStatus, requiredThermalStatus))
        }
        minHeadroomMargin?.let { required ->
            // Platform headroom is closeness to throttling (0 cool, 1 throttling now); the policy is a margin.
            val headroom = c.thermalHeadroom
            if (headroom != null && 1f - headroom < required) add(UnmetCondition.HeadroomTooLow(1f - headroom, required))
        }
        if (requireUnplugged && c.plugged) add(UnmetCondition.Charging)
        if (c.batteryPercent == null || c.batteryPercent < minBatteryPercent) {
            add(UnmetCondition.BatteryTooLow(c.batteryPercent, minBatteryPercent))
        }
        val screenOk = when (screenPolicy) {
            ScreenPolicy.OFF -> !c.screenOn
            ScreenPolicy.ON, ScreenPolicy.ON_DIMMED -> c.screenOn
        }
        if (!screenOk) add(UnmetCondition.ScreenState(c.screenOn, screenPolicy))
    }

    companion object {
        val DEFAULT = PreconditionPolicy()
    }
}

/** One instantaneous reading of everything the gate checks. */
data class DeviceConditions(
    val thermalStatus: ThermalStatus,
    /** PowerManager.getThermalHeadroom; null when unavailable or NaN. */
    val thermalHeadroom: Float?,
    /** Null when the platform does not report a level. */
    val batteryPercent: Int?,
    val plugged: Boolean,
    val screenOn: Boolean,
    /** Tenths of a degree Celsius, from the battery broadcast; null when absent. */
    val batteryTemperatureDeciC: Int?,
)

sealed interface UnmetCondition {
    data class ThermalStatusTooHigh(val current: ThermalStatus, val required: ThermalStatus) : UnmetCondition
    data class HeadroomTooLow(val margin: Float, val required: Float) : UnmetCondition
    data object Charging : UnmetCondition
    data class BatteryTooLow(val current: Int?, val required: Int) : UnmetCondition
    data class ScreenState(val screenOn: Boolean, val required: ScreenPolicy) : UnmetCondition

    /** Says what to do, not only what is wrong. Recorded verbatim in the bundle. */
    val description: String
        get() = when (this) {
            is ThermalStatusTooHigh -> "Thermal status is $current; need $required or cooler"
            // The margin is rounded down so it is never shown as reaching a threshold it has not.
            is HeadroomTooLow -> "Thermal headroom margin is ${formatMargin(margin)}; need >= ${formatMargin(required)}"
            Charging -> "Device is charging; unplug it"
            is BatteryTooLow -> "Battery at ${current?.let { "$it%" } ?: "unknown level"}; need >= $required%"
            is ScreenState -> if (screenOn) {
                "Screen is on; press the power button. The run starts by itself with the screen off"
            } else {
                if (required == ScreenPolicy.ON_DIMMED) {
                    "Screen is off; turn it on and keep VeriSpec open. It holds the screen on, dimmed"
                } else {
                    "Screen is off; turn it on and leave it on"
                }
            }
        }

    private companion object {
        fun formatMargin(m: Float): String {
            // Slack absorbs float error: 1 - 0.6f is 0.39999998, which is 0.40.
            val hundredths = floor(m * 100.0 + 1e-4).toLong()
            return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
        }
    }
}

sealed interface PreconditionState {
    data class Waiting(
        val unmet: List<UnmetCondition>,
        /** How long every condition has held so far; resets whenever one fails. */
        val heldMillis: Long,
        val waitedMillis: Long,
        val latest: DeviceConditions,
    ) : PreconditionState

    data class Satisfied(val waitedMillis: Long, val at: DeviceConditions) : PreconditionState

    /** [heldMillis] is non-zero when every condition held but not yet for the whole window. */
    data class TimedOut(
        val waitedMillis: Long,
        val unmet: List<UnmetCondition>,
        val heldMillis: Long,
        val latest: DeviceConditions,
    ) : PreconditionState
}

/** What the gate found, recorded in the observations so a reader can tell a cold start from a warm one. */
data class PreconditionOutcome(
    val satisfied: Boolean,
    val waitedMillis: Long,
    /** Descriptions of the conditions still unmet when the run started; empty when satisfied. */
    val unmetAtStart: List<String>,
    val conditionsAtStart: DeviceConditions,
)
