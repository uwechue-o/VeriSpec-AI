package ai.verispec.data.telemetry

import ai.verispec.core.domain.model.PowerSample
import ai.verispec.core.domain.port.PowerTelemetrySource
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BatteryManager sampling at 1 Hz. Current sign conventions differ per vendor
 * (some report discharge as negative); we record the raw value and let the
 * analysis side take the magnitude. Charging state is recorded because it
 * invalidates power comparisons; screen state for the same reason, the panel draws
 * power and heats the device.
 */
@Singleton
class AndroidPowerTelemetrySource @Inject constructor(
    @ApplicationContext private val context: Context,
) : PowerTelemetrySource {

    private val batteryManager: BatteryManager by lazy { context.getSystemService(BatteryManager::class.java) }
    private val powerManager: PowerManager by lazy { context.getSystemService(PowerManager::class.java) }

    override fun stream(): Flow<PowerSample> = flow {
        while (true) {
            emit(sample())
            delay(TICK_MILLIS)
        }
    }.flowOn(Dispatchers.IO)

    fun sample(): PowerSample {
        val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val current = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Long.MIN_VALUE && it != 0L }
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        return PowerSample(
            elapsedMillis = 0, // stamped by the run loop
            currentMicroAmps = current,
            voltageMilliVolts = voltage,
            batteryLevelPercent = level,
            isCharging = batteryManager.isCharging,
            batteryTemperatureDeciC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE },
            screenOn = powerManager.isInteractive,
        )
    }

    companion object {
        const val TICK_MILLIS = 1_000L
    }
}
