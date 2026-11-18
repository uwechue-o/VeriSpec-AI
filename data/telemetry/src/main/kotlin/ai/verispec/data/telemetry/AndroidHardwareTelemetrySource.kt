package ai.verispec.data.telemetry

import ai.verispec.core.domain.model.ClockReading
import ai.verispec.core.domain.model.TelemetryInventory
import ai.verispec.core.domain.port.HardwareTelemetrySource
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** CPU/GPU clocks per iteration and the inventory of what this device lets the app read. */
@Singleton
class AndroidHardwareTelemetrySource @Inject constructor(
    @ApplicationContext private val context: Context,
) : HardwareTelemetrySource {

    override fun clocks(): ClockReading? {
        val cpu = SysfsSensors.cpuKhz()
        val gpu = SysfsSensors.gpuKhz()
        return if (cpu == null && gpu == null) null else ClockReading(cpu, gpu)
    }

    override suspend fun inventory(): TelemetryInventory = withContext(Dispatchers.IO) {
        val readable = mutableListOf<String>()
        val unavailable = mutableListOf<String>()

        val cores = SysfsSensors.cpuFreqFiles.size
        if (SysfsSensors.cpuKhz() != null) {
            readable += "cpu clock ($cores cores)"
        } else {
            unavailable += "cpu clock: /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq not readable for every core"
        }

        val gpu = SysfsSensors.gpuFreqFile
        if (gpu != null) readable += "gpu clock (${gpu.path})" else unavailable += "gpu clock: no readable kgsl, Mali or devfreq node"

        val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky?.hasExtra(BatteryManager.EXTRA_TEMPERATURE) == true) {
            readable += "battery temperature"
        } else {
            unavailable += "battery temperature: absent from the battery broadcast"
        }

        val zones = SysfsSensors.zones
        if (zones.isNotEmpty()) {
            zones.forEach { readable += "thermal zone: ${it.type}" }
        } else {
            unavailable += if (SysfsSensors.zonesDenied) {
                "thermal zones: /sys/class/thermal denied to apps"
            } else {
                "thermal zones: /sys/class/thermal absent"
            }
        }

        TelemetryInventory(readable = readable, unavailable = unavailable)
    }
}
