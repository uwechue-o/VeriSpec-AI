package ai.verispec.data.attestation

import ai.verispec.core.domain.model.DeviceProfile
import ai.verispec.core.domain.model.SocIdentity
import ai.verispec.core.domain.port.DeviceProfiler
import ai.verispec.core.domain.port.ThermalTelemetrySource
import ai.verispec.data.runtime.api.platform.SystemProps
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures what the machine actually is, at run time. Every field that cannot be
 * determined is recorded as null, never guessed.
 */
@Singleton
class AndroidDeviceProfiler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val thermal: ThermalTelemetrySource,
) : DeviceProfiler {

    override suspend fun profile(): DeviceProfile = withContext(Dispatchers.IO) {
        val gl = GlInfoProbe.probe()
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        DeviceProfile(
            fingerprint = Build.FINGERPRINT,
            manufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            soc = soc(),
            npuDriverVersion = npuDriverVersion(),
            gpuDriverVersion = gl.version,
            gpuRenderer = gl.renderer,
            androidRelease = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            securityPatch = parsePatch(Build.VERSION.SECURITY_PATCH),
            totalRamBytes = memory.totalMem,
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            thermalHalVersion = thermal.halVersion(),
            kernelVersion = System.getProperty("os.version"),
        )
    }

    private fun soc(): SocIdentity {
        val vendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null
        val model = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        return SocIdentity(
            vendor = vendor?.takeUnless { it == Build.UNKNOWN } ?: SystemProps.firstNonBlank("ro.soc.manufacturer", "ro.hardware.chipname") ?: "unknown",
            model = model?.takeUnless { it == Build.UNKNOWN } ?: SystemProps.firstNonBlank("ro.soc.model", "ro.board.platform", "ro.hardware") ?: "unknown",
            revision = SystemProps.firstNonBlank("ro.revision", "ro.boot.hardware.revision"),
        )
    }

    /**
     * There is no standard NPU driver property. We probe the common vendor
     * properties and library markers and record what we find, labelled by source.
     */
    private fun npuDriverVersion(): String? {
        val props = listOf(
            "ro.vendor.qti.npu.version", "vendor.qti.npu.version", "ro.hardware.npu",
            "ro.vendor.npu.version", "ro.vendor.mediatek.apusys.version", "ro.vendor.samsung.npu.version",
            "ro.hardware.gpu", "ro.vendor.gpu.version",
        )
        for (p in props) SystemProps.get(p)?.let { return "$p=$it" }
        return when {
            SystemProps.libraryExists("libQnnHtp.so", "libQnnHtpV73Stub.so", "libQnnHtpV75Stub.so") -> "qnn-htp:present(version unreported)"
            SystemProps.libraryExists("libneuron_runtime.so", "libneuronusdk_adapter.mtk.so") -> "mediatek-neuron:present(version unreported)"
            SystemProps.libraryExists("libeden_rt_stub.so", "libenn_public_api_ndk_v1.so") -> "samsung-enn:present(version unreported)"
            SystemProps.libraryExists("libedgetpu_util.so", "libedgetpu_client.google.so") -> "google-edgetpu:present(version unreported)"
            else -> null
        }
    }

    companion object {
        fun parsePatch(text: String?): LocalDate =
            runCatching { LocalDate.parse(text ?: "") }.getOrElse { LocalDate(1970, 1, 1) }
    }
}
