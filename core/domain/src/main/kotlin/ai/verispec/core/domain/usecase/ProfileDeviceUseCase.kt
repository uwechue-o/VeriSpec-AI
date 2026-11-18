package ai.verispec.core.domain.usecase

import ai.verispec.core.domain.model.DeviceProfile
import ai.verispec.core.domain.port.DeviceProfiler

/** Captures the DeviceProfile, including driver/firmware versions, at run time. */
class ProfileDeviceUseCase(private val profiler: DeviceProfiler) {
    suspend operator fun invoke(): DeviceProfile = profiler.profile()
}
