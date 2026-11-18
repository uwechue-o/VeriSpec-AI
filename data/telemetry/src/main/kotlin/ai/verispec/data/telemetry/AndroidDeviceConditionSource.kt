package ai.verispec.data.telemetry

import ai.verispec.core.domain.model.DeviceConditions
import ai.verispec.core.domain.port.DeviceConditionSource
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One reading of what the precondition gate checks: platform thermal status and
 * headroom, battery level, charger, battery temperature and screen state. All
 * come from public platform APIs, so unlike the kernel thermal zones none of
 * them is blocked by SELinux.
 */
@Singleton
class AndroidDeviceConditionSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceConditionSource {

    private val powerManager: PowerManager by lazy { context.getSystemService(PowerManager::class.java) }
    private val batteryManager: BatteryManager by lazy { context.getSystemService(BatteryManager::class.java) }

    override suspend fun read(): DeviceConditions = withContext(Dispatchers.Default) {
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        DeviceConditions(
            thermalStatus = AndroidThermalTelemetrySource.mapStatus(powerManager.currentThermalStatus),
            thermalHeadroom = headroom(),
            batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 },
            // Any charger counts, wireless included: all of them heat the device.
            plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            screenOn = powerManager.isInteractive,
            batteryTemperatureDeciC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE },
        )
    }

    private fun headroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val h = runCatching { powerManager.getThermalHeadroom(0) }.getOrNull() ?: return null
        return if (h.isNaN()) null else h
    }
}
