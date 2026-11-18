package ai.verispec.data.telemetry.di

import ai.verispec.core.domain.port.DeviceConditionSource
import ai.verispec.core.domain.port.HardwareTelemetrySource
import ai.verispec.core.domain.port.MemoryTelemetrySource
import ai.verispec.core.domain.port.PowerTelemetrySource
import ai.verispec.core.domain.port.ThermalTelemetrySource
import ai.verispec.data.telemetry.AndroidDeviceConditionSource
import ai.verispec.data.telemetry.AndroidHardwareTelemetrySource
import ai.verispec.data.telemetry.AndroidMemoryTelemetrySource
import ai.verispec.data.telemetry.AndroidPowerTelemetrySource
import ai.verispec.data.telemetry.AndroidThermalTelemetrySource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class TelemetryModule {
    @Binds abstract fun thermal(impl: AndroidThermalTelemetrySource): ThermalTelemetrySource
    @Binds abstract fun power(impl: AndroidPowerTelemetrySource): PowerTelemetrySource
    @Binds abstract fun memory(impl: AndroidMemoryTelemetrySource): MemoryTelemetrySource
    @Binds abstract fun conditions(impl: AndroidDeviceConditionSource): DeviceConditionSource
    @Binds abstract fun hardware(impl: AndroidHardwareTelemetrySource): HardwareTelemetrySource
}
