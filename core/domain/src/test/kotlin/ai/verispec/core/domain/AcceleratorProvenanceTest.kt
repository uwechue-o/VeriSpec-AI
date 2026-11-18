package ai.verispec.core.domain

import ai.verispec.core.domain.model.AcceleratorDevice
import ai.verispec.core.domain.model.AcceleratorType
import ai.verispec.core.domain.model.BackendRef
import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.DeviceProfile
import ai.verispec.core.domain.model.LoadedModel
import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.ModelFormat
import ai.verispec.core.domain.model.ModelRef
import ai.verispec.core.domain.model.QuantizationSpec
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.RunSchedule
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.model.SemVer
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.model.SocIdentity
import ai.verispec.core.domain.model.UnsignedBundle
import ai.verispec.core.domain.model.Workload
import ai.verispec.core.domain.model.acceleratorSummary
import ai.verispec.core.domain.port.Canonicalizer
import ai.verispec.core.domain.port.Clock
import ai.verispec.core.domain.port.Hasher
import ai.verispec.core.domain.usecase.AssembleEvidenceUseCase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.datetime.LocalDate

/**
 * A manifest can name one device behind the NNAPI delegate. The provenance must say
 * what was asked for, what the delegate actually ran on and what the platform listed,
 * and a name the delegate refused must be recorded as a fallback, not hidden.
 */
class AcceleratorProvenanceTest : StringSpec({

    "an accelerator can only be named for NNAPI, and never blank" {
        BackendRef(RuntimeId.LITERT, SemVer(1, 4, 0), Delegate.NNAPI, 4, accelerator = "qti-hta").accelerator shouldBe "qti-hta"
        shouldThrow<IllegalArgumentException> { BackendRef(RuntimeId.LITERT, SemVer(1, 4, 0), Delegate.CPU, 4, accelerator = "qti-hta") }
        shouldThrow<IllegalArgumentException> { BackendRef(RuntimeId.LITERT, SemVer(1, 4, 0), Delegate.NNAPI, 4, accelerator = " ") }
    }

    "a named accelerator the delegate ran on is recorded with the devices listed, and no fallback note" {
        val p = provenance(manifest("qti-gpu"), loaded(Delegate.NNAPI, used = "qti-gpu"))
        p.accelerator shouldBe "qti-gpu"
        p.acceleratorActuallyUsed shouldBe "qti-gpu"
        p.acceleratorsAvailable shouldBe DEVICES
        p.notes.shouldBeEmpty()
    }

    "a named accelerator that was refused is recorded as a fallback" {
        val p = provenance(manifest("qti-hta"), loaded(Delegate.CPU, used = null))
        p.accelerator shouldBe "qti-hta"
        p.acceleratorActuallyUsed shouldBe null
        p.notes shouldContainExactly listOf(
            "Accelerator fallback: requested qti-hta, which the NNAPI delegate did not run on",
            "Delegate fallback: requested NNAPI, executed on CPU",
        )
    }

    "what the adapter observed while loading reaches the provenance notes" {
        val loaded = object : LoadedModel by loaded(Delegate.NNAPI, used = "qti-gpu") {
            override val loadNotes = listOf("qti-gpu took all 62 operations in 1 partition(s) (LiteRT log)")
        }
        provenance(manifest("qti-gpu"), loaded).notes shouldContainExactly listOf("qti-gpu took all 62 operations in 1 partition(s) (LiteRT log)")
    }

    "without a name the platform's choice is not claimed, but the devices are still listed" {
        val p = provenance(manifest(null), loaded(Delegate.NNAPI, used = null))
        p.accelerator shouldBe null
        p.acceleratorActuallyUsed shouldBe null
        p.acceleratorsAvailable shouldBe DEVICES
        p.notes.shouldBeEmpty()
        p.acceleratorSummary() shouldBe
            "none named, so NNAPI chose and did not report which device; listed: qti-default (other), qti-gpu (gpu), qti-hta (accelerator)"
    }

    "the summary credits a named device only when the delegate was bound to it, and is absent for other runs" {
        provenance(manifest("qti-gpu"), loaded(Delegate.NNAPI, used = "qti-gpu")).acceleratorSummary()!! shouldStartWith "qti-gpu named, delegate bound to it; listed: "
        provenance(manifest("qti-hta"), loaded(Delegate.CPU, used = null)).acceleratorSummary()!! shouldStartWith "qti-hta named but not used; listed: "
        provenance(manifest(null), loaded(Delegate.NNAPI, used = null)).copy(acceleratorsAvailable = null).acceleratorSummary() shouldBe null
    }
})

private val DEVICES = listOf(
    AcceleratorDevice("qti-default", AcceleratorType.OTHER, "1.3-23", 31),
    AcceleratorDevice("qti-gpu", AcceleratorType.GPU, "1.3-23", 31),
    AcceleratorDevice("qti-hta", AcceleratorType.ACCELERATOR, "1.3-23", 31),
)

private fun manifest(accelerator: String?) = RunManifest(
    manifestId = ManifestId("a".repeat(64)),
    model = ModelRef(ModelFormat.TFLITE, Sha256("b".repeat(64)), null, QuantizationSpec.INT8, null),
    backend = BackendRef(RuntimeId.LITERT, SemVer(1, 4, 0), Delegate.NNAPI, threadCount = 4, accelerator = accelerator),
    workload = Workload.TensorInference(listOf(1, 224, 224, 3)),
    schedule = RunSchedule(30, 3),
    seed = 42,
    toolVersion = SemVer(0, 1, 0),
)

private fun loaded(effective: Delegate, used: String?) = object : LoadedModel {
    override val ref = manifest(null).model
    override val requestedDelegate = Delegate.NNAPI
    override val effectiveDelegate = effective
    override val runtimeVersionObserved = "1.4.0"
    override val acceleratorUsed = used
    override val acceleratorsAvailable = DEVICES
}

private fun provenance(manifest: RunManifest, loaded: LoadedModel) =
    AssembleEvidenceUseCase(NoCanonicalizer, NoHasher, FixedClock).buildProvenance(manifest, DEVICE, loaded, emptyList())

private val DEVICE = DeviceProfile(
    fingerprint = "OnePlus/OnePlus8T/OnePlus8T:14/UKQ1.230924.001/R.1:user/release-keys",
    manufacturer = "OnePlus",
    deviceModel = "KB2005",
    soc = SocIdentity("Qualcomm", "SM8250", "0"),
    npuDriverVersion = null,
    gpuDriverVersion = null,
    gpuRenderer = null,
    androidRelease = "14",
    apiLevel = 34,
    securityPatch = LocalDate(2024, 8, 5),
    totalRamBytes = 12L shl 30,
    cpuCoreCount = 8,
    supportedAbis = listOf("arm64-v8a"),
    thermalHalVersion = null,
    kernelVersion = null,
)

private object NoCanonicalizer : Canonicalizer {
    override fun manifestBytes(manifest: RunManifest) = ByteArray(0)
    override fun bundleBytes(bundle: UnsignedBundle) = ByteArray(0)
}

private val NoHasher = Hasher { Sha256("0".repeat(64)) }

private object FixedClock : Clock {
    override fun epochMillis() = 1_800_000_000_000L
    override fun monotonicNanos() = 0L
}
