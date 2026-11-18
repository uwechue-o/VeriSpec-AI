package ai.verispec.data.runtime.litert

import ai.verispec.core.domain.model.AcceleratorDevice
import ai.verispec.core.domain.model.AcceleratorType

/**
 * The NNAPI devices the platform lists (on a Snapdragon 865: qti-default, qti-dsp,
 * qti-gpu, qti-hta), read through the NDK because LiteRT's Java API cannot list them.
 * A manifest may name one of them; the bundle records them all.
 */
internal object NnapiDevices {

    private val loaded: Boolean by lazy { runCatching { System.loadLibrary("verispec_nnapi") }.isSuccess }

    /** Null when the platform can't be asked: the library is missing (e.g. a non-arm64 ABI) or NNAPI errors. */
    fun list(): List<AcceleratorDevice>? {
        if (!loaded) return null
        val raw = runCatching { nativeList() }.getOrNull() ?: return null
        return raw.map { parse(String(it, Charsets.UTF_8)) }
    }

    /** One "name\ttype\tversion\tfeatureLevel" line from the native side. */
    fun parse(line: String): AcceleratorDevice {
        val f = line.split('\t')
        return AcceleratorDevice(
            name = f[0],
            type = when (f.getOrNull(1)?.toIntOrNull()) {
                // ANEURALNETWORKS_DEVICE_* in NeuralNetworks.h
                1 -> AcceleratorType.OTHER
                2 -> AcceleratorType.CPU
                3 -> AcceleratorType.GPU
                4 -> AcceleratorType.ACCELERATOR
                else -> AcceleratorType.UNKNOWN
            },
            version = f.getOrNull(2)?.takeIf { it.isNotEmpty() },
            featureLevel = f.getOrNull(3)?.toLongOrNull()?.takeIf { it >= 0 },
        )
    }

    @JvmStatic
    private external fun nativeList(): Array<ByteArray>?
}
