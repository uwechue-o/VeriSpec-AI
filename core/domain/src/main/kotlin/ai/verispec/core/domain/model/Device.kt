package ai.verispec.core.domain.model

import kotlinx.datetime.LocalDate

data class SocIdentity(
    val vendor: String,
    val model: String,
    val revision: String?,
)

/** Everything observed about the machine, captured at run time, not assumed. */
data class DeviceProfile(
    val fingerprint: String,
    val manufacturer: String,
    val deviceModel: String,
    val soc: SocIdentity,
    val npuDriverVersion: String?,
    val gpuDriverVersion: String?,
    val gpuRenderer: String?,
    val androidRelease: String,
    val apiLevel: Int,
    val securityPatch: LocalDate,
    val totalRamBytes: Long,
    val cpuCoreCount: Int,
    val supportedAbis: List<String>,
    val thermalHalVersion: String?,
    val kernelVersion: String?,
)
