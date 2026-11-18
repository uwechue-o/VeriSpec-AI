package ai.verispec.data.runtime.litert

import ai.verispec.core.domain.model.AcceleratorDevice
import ai.verispec.core.domain.model.AcceleratorType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NnapiDevicesTest {

    @Test
    fun `a full line maps every field, with the NDK device type`() {
        assertThat(NnapiDevices.parse("qti-hta\t4\t1.3-23:build_kona\t30"))
            .isEqualTo(AcceleratorDevice("qti-hta", AcceleratorType.ACCELERATOR, "1.3-23:build_kona", 30))
        assertThat(NnapiDevices.parse("qti-gpu\t3\tv\t30").type).isEqualTo(AcceleratorType.GPU)
        assertThat(NnapiDevices.parse("nnapi-reference\t2\tv\t30").type).isEqualTo(AcceleratorType.CPU)
        assertThat(NnapiDevices.parse("qti-default\t1\tv\t30").type).isEqualTo(AcceleratorType.OTHER)
    }

    @Test
    fun `what a driver did not report is null or UNKNOWN, never invented`() {
        assertThat(NnapiDevices.parse("vendor-npu\t0\t\t-1"))
            .isEqualTo(AcceleratorDevice("vendor-npu", AcceleratorType.UNKNOWN, null, null))
    }

    @Test
    fun `off the device the list is unavailable rather than empty`() {
        // The JVM test runtime has no libverispec_nnapi, which is the non-arm64 case.
        assertThat(NnapiDevices.list()).isNull()
    }
}
