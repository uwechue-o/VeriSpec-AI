package ai.verispec.tools.analysis

import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.Sha256
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class DivergenceTest : StringSpec({

    val divergence = ComputeDivergenceUseCase()

    "identical devices produce perfect agreement" {
        val a = TestBundles.bundle("device-a", "Tensor G3")
        val b = TestBundles.bundle("device-b", "Snapdragon 8 Gen 3")
        val report = divergence(listOf(a, b))
        report.bitIdenticalAcrossFleet shouldBe true
        report.pairwise.single().digestAgreementRate shouldBe 1.0
        report.pairwise.single().meanCosineSimilarity!! shouldBeGreaterThan 0.999999
    }

    "a drifting device is detected and the first divergent iteration named" {
        val reference = TestBundles.bundle("device-a", "Tensor G3")
        val drifting = TestBundles.bundle("device-b", "Snapdragon 8 Gen 3", driftFromIteration = 4, driftMagnitude = 0.01f)
        val report = divergence(listOf(reference, drifting))
        val pair = report.pairwise.single()
        report.bitIdenticalAcrossFleet shouldBe false
        pair.firstDivergentIteration shouldBe 4
        pair.digestAgreementRate shouldBeLessThan 1.0
        pair.maxLInfDelta!! shouldBeGreaterThan 0.0
        pair.minCosineSimilarity.shouldNotBeNull()
    }

    "bundles from different manifests are refused" {
        val a = TestBundles.bundle("device-a", "Tensor G3", seed = 1)
        val b = TestBundles.bundle("device-b", "Snapdragon 8 Gen 3", seed = 2)
        shouldThrow<ComputeDivergenceUseCase.IncomparableBundlesException> { divergence(listOf(a, b)) }
    }

    "two bundles from the same device produce a warning, not a silent result" {
        val a = TestBundles.bundle("device-a", "Tensor G3")
        val b = TestBundles.bundle("device-a", "Tensor G3")
        val report = divergence(listOf(a, b.copy(bundleId = BundleId(Sha256("b".repeat(64)).hex))))
        report.warnings.any { it.contains("same device") } shouldBe true
    }

    "decay analysis reports a negative slope for a throttling run" {
        val decay = ComputeDecayUseCase()(TestBundles.bundle("device-a", "Tensor G3"))
        decay.shouldNotBeNull()
        decay.slopePerIteration shouldBeLessThan 0.0
        // The fixture declines and then holds, so it has a settled tail and a measurable decay.
        decay.steadyStateTokensPerSecond.shouldNotBeNull()
        decay.decayPercent.shouldNotBeNull() shouldBeGreaterThan 0.0
        decay.throttledFraction shouldBeGreaterThan 0.0
        decay.validity shouldBe ai.verispec.core.domain.model.EnvelopeValidity.NO_PRECONDITION_GATE
    }
})
