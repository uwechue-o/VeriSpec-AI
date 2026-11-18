package ai.verispec.data.telemetry

import java.io.File

/**
 * Kernel sensor and clock nodes, discovered once per process. Which nodes an app
 * may read varies by vendor and SELinux policy: a Pixel 6 allows cpufreq and the
 * Mali clock but denies /sys/class/thermal. Every read here is best-effort and
 * returns null rather than throwing.
 */
internal object SysfsSensors {

    data class Zone(val type: String, val temp: File)

    /** Thermal zones whose temperature read back plausibly at discovery. */
    val zones: List<Zone> by lazy {
        val root = File("/sys/class/thermal")
        val dirs = root.listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
            .sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
            .take(MAX_ZONES)
        dirs.mapNotNull { dir ->
            val type = runCatching { File(dir, "type").readText().trim() }.getOrNull() ?: return@mapNotNull null
            val temp = File(dir, "temp")
            if (celsius(temp) == null) null else Zone(type, temp)
        }
    }

    /** True when the thermal root exists but its zones could not be listed or read. */
    val zonesDenied: Boolean by lazy { zones.isEmpty() && File("/sys/class/thermal").exists() }

    fun zoneTemperatures(): Map<String, Float> {
        val out = sortedMapOf<String, Float>()
        for (z in zones) celsius(z.temp)?.let { out[z.type] = it }
        return out
    }

    /** scaling_cur_freq per core, in core order, including cores that are offline now. */
    val cpuFreqFiles: List<File> by lazy {
        File("/sys/devices/system/cpu").listFiles { f -> CPU_DIR.matches(f.name) }.orEmpty()
            .sortedBy { it.name.removePrefix("cpu").toInt() }
            .map { File(it, "cpufreq/scaling_cur_freq") }
    }

    /** Null unless every core reads: a partial list would be mistaken for the whole. */
    fun cpuKhz(): List<Long>? {
        if (cpuFreqFiles.isEmpty()) return null
        return cpuFreqFiles.map { readLong(it)?.takeIf { v -> v > 0 } ?: return null }
    }

    /** The first GPU clock node that reads a positive value. */
    val gpuFreqFile: File? by lazy { gpuCandidates().firstOrNull { (readLong(it) ?: 0) > 0 } }

    fun gpuKhz(): Long? = gpuFreqFile?.let { readLong(it) }?.takeIf { it > 0 }?.let(::normalizeToKhz)

    /**
     * Adreno (kgsl) reports Hz, Mali usually kHz, some devfreq nodes Hz, a few
     * vendor nodes MHz. GPU clocks span roughly 100 MHz to 1.5 GHz, so the
     * magnitude says which unit a node uses.
     */
    fun normalizeToKhz(raw: Long): Long = when {
        raw >= 10_000_000 -> raw / 1_000 // Hz
        raw < 10_000 -> raw * 1_000 // MHz
        else -> raw // kHz
    }

    private fun gpuCandidates(): List<File> = buildList {
        add(File("/sys/class/kgsl/kgsl-3d0/gpuclk"))
        add(File("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"))
        File("/sys/devices/platform").listFiles { f -> f.name.contains("mali") }.orEmpty()
            .forEach { add(File(it, "cur_freq")) }
        // Pixel's Tensor G1 Mali; listed by name too because /sys/devices/platform may not be listable.
        add(File("/sys/devices/platform/1c500000.mali/cur_freq"))
        File("/sys/class/devfreq").listFiles { f -> f.name.contains("gpu") || f.name.contains("mali") }.orEmpty()
            .forEach { add(File(it, "cur_freq")) }
        add(File("/sys/kernel/gpu/gpu_clock"))
    }

    private fun celsius(file: File): Float? {
        val raw = readLong(file) ?: return null
        // Most kernels report millidegrees; a few report degrees.
        val c = if (raw > 1_000 || raw < -1_000) raw / 1_000f else raw.toFloat()
        return c.takeIf { it in -40f..150f }
    }

    private fun readLong(file: File): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()

    private val CPU_DIR = Regex("cpu[0-9]+")
    const val MAX_ZONES = 48
}
