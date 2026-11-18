package ai.verispec.data.telemetry

import ai.verispec.core.domain.model.ThermalSample
import ai.verispec.core.domain.model.ThermalStatus
import ai.verispec.core.domain.port.ThermalTelemetrySource
import ai.verispec.data.runtime.api.platform.SystemProps
import android.content.Context
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PowerManager thermal status + headroom, plus a best-effort read of the kernel
 * thermal zones (discovered once; see [SysfsSensors]). Emits on every platform status change and on a 1 Hz tick so a
 * timeline exists even when the status never changes.
 */
@Singleton
class AndroidThermalTelemetrySource @Inject constructor(
    @ApplicationContext private val context: Context,
) : ThermalTelemetrySource {

    private val powerManager: PowerManager by lazy { context.getSystemService(PowerManager::class.java) }

    override fun stream(): Flow<ThermalSample> = callbackFlow {
        val listener = PowerManager.OnThermalStatusChangedListener { status -> trySend(sample(status)) }
        powerManager.addThermalStatusListener(listener)
        trySend(sample(powerManager.currentThermalStatus))

        val ticker = launch {
            while (isActive) {
                delay(TICK_MILLIS)
                trySend(sample(powerManager.currentThermalStatus))
            }
        }
        awaitClose {
            ticker.cancel()
            powerManager.removeThermalStatusListener(listener)
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun halVersion(): String? = withContext(Dispatchers.IO) {
        SystemProps.firstNonBlank("ro.vendor.thermal.hal.version", "ro.hardware.thermal", "ro.thermal.hal")
    }

    private fun sample(platformStatus: Int): ThermalSample = ThermalSample(
        elapsedMillis = 0, // stamped by the run loop against its own monotonic clock
        status = mapStatus(platformStatus),
        headroom = headroom(),
        zoneTemperaturesCelsius = SysfsSensors.zoneTemperatures(),
    )

    private fun headroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val h = runCatching { powerManager.getThermalHeadroom(0) }.getOrNull() ?: return null
        return if (h.isNaN()) null else h
    }

    companion object {
        const val TICK_MILLIS = 1_000L

        fun mapStatus(status: Int): ThermalStatus = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
            else -> ThermalStatus.UNKNOWN
        }
    }
}
