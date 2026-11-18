package ai.verispec.core.evidence

import ai.verispec.core.domain.model.SemVer
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Schema 1.1.0 added the optional precondition gate. These tests pin the
 * promise that made it a minor version: bundles and manifests written before
 * it re-encode to the same bytes, so their ids and signatures still hold.
 * The golden files were written by the 1.0.0 code and must never be regenerated.
 */
class SchemaCompatibilityTest : StringSpec({

    fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }.bufferedReader().readText()

    fun schemaErrors(schemaPath: String, json: String): List<String> =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(resource(schemaPath))
            .validate(json, InputFormat.JSON).map { it.toString() }

    "a 1.0.0 bundle re-encodes to its original bytes and its id still matches" {
        val golden = resource("/golden/bundle-1.0.0.json")
        val decoded = BundleCodec.decode(golden)
        decoded.schemaVersion shouldBe SemVer(1, 0, 0)
        BundleCodec.encode(decoded).toString(Charsets.UTF_8) shouldBe golden
        Hashing.sha256(EvidenceCanonicalizer.bundleBytes(decoded.unsigned())).hex shouldBe decoded.bundleId.value
    }

    "a 1.0.0 manifest keeps its id" {
        val decoded = BundleCodec.decodeManifest(resource("/golden/manifest-1.0.0.json"))
        val id = resource("/golden/manifest-1.0.0.id").trim()
        decoded.manifestId.value shouldBe id
        Hashing.sha256(EvidenceCanonicalizer.manifestBytes(decoded)).hex shouldBe id
    }

    "an ungated manifest writes no preconditions key at all" {
        EvidenceCanonicalizer.manifestBytes(Fixtures.manifest()).toString(Charsets.UTF_8) shouldNotContain "preconditions"
        BundleCodec.encode(Fixtures.bundle()).toString(Charsets.UTF_8) shouldNotContain "preconditions"
    }

    "the gate is part of the manifest identity" {
        Fixtures.gatedManifest().manifestId shouldNotBe Fixtures.manifest().manifestId
        EvidenceCanonicalizer.manifestBytes(Fixtures.gatedManifest()).toString(Charsets.UTF_8) shouldContain "\"preconditions\""
    }

    "a gated bundle round-trips with identical canonical bytes" {
        val bundle = Fixtures.bundle(Fixtures.gatedManifest())
        bundle.observations.preconditions shouldNotBe null
        val bytes = BundleCodec.encode(bundle)
        val decoded = BundleCodec.decode(bytes)
        decoded shouldBe bundle
        BundleCodec.encode(decoded) shouldBe bytes
        Hashing.sha256(EvidenceCanonicalizer.bundleBytes(decoded.unsigned())).hex shouldBe decoded.bundleId.value
    }

    "a gated bundle validates against the 1.1.0 schema" {
        schemaErrors("/schemas/evidence-bundle-1.1.0.schema.json", BundleCodec.encodePretty(Fixtures.bundle(Fixtures.gatedManifest())))
            .shouldBeEmpty()
    }

    "a 1.0.0 bundle is also a valid 1.1.0 bundle" {
        schemaErrors("/schemas/evidence-bundle-1.1.0.schema.json", resource("/golden/bundle-1.0.0.json")).shouldBeEmpty()
    }

    // 1.2.0: hardware telemetry and the ON_DIMMED screen policy, added the same way.

    "a bundle without hardware telemetry writes none of the 1.2.0 keys" {
        val json = BundleCodec.encode(Fixtures.bundle(Fixtures.gatedManifest())).toString(Charsets.UTF_8)
        listOf("\"clocks\"", "\"telemetry\"", "cpuKhz", "gpuKhz").forEach { json shouldNotContain it }
        // The gate's start conditions already carry these names, so look only at the power timeline.
        val power = json.substringAfter("\"powerTimeline\":").substringBefore("]")
        power shouldNotContain "batteryTemperatureDeciC"
        power shouldNotContain "screenOn"
    }

    "a bundle with hardware telemetry round-trips byte-identically and keeps its id" {
        val bundle = Fixtures.bundle(Fixtures.dimmedManifest(), hardware = true)
        val bytes = BundleCodec.encode(bundle)
        val decoded = BundleCodec.decode(bytes)
        decoded shouldBe bundle
        BundleCodec.encode(decoded) shouldBe bytes
        Hashing.sha256(EvidenceCanonicalizer.bundleBytes(decoded.unsigned())).hex shouldBe decoded.bundleId.value
        // A partial CPU reading is absent, not an empty or null list.
        bytes.toString(Charsets.UTF_8) shouldContain "\"clocks\":{\"gpuKhz\":251000}"
    }

    "a 1.2.0 bundle validates against the 1.2.0 schema, and older bundles still do" {
        schemaErrors("/schemas/evidence-bundle-1.2.0.schema.json", BundleCodec.encodePretty(Fixtures.bundle(Fixtures.dimmedManifest(), hardware = true)))
            .shouldBeEmpty()
        schemaErrors("/schemas/evidence-bundle-1.2.0.schema.json", BundleCodec.encodePretty(Fixtures.bundle(Fixtures.gatedManifest())))
            .shouldBeEmpty()
        schemaErrors("/schemas/evidence-bundle-1.2.0.schema.json", resource("/golden/bundle-1.0.0.json")).shouldBeEmpty()
    }

    "a 1.3.0 accelerator bundle round-trips byte for byte and records the device in manifest and provenance" {
        val bundle = Fixtures.bundle(Fixtures.acceleratorManifest())
        val bytes = BundleCodec.encode(bundle)
        val decoded = BundleCodec.decode(bytes)
        decoded shouldBe bundle
        BundleCodec.encode(decoded) shouldBe bytes
        Hashing.sha256(EvidenceCanonicalizer.bundleBytes(decoded.unsigned())).hex shouldBe decoded.bundleId.value
        decoded.manifest.backend.accelerator shouldBe "qti-hta"
        decoded.provenance.acceleratorActuallyUsed shouldBe "qti-hta"
        decoded.provenance.acceleratorsAvailable shouldBe Fixtures.snapdragon865NnapiDevices
    }

    "without a named accelerator the 1.3.0 fields are absent, so manifest ids do not move" {
        val json = BundleCodec.encode(Fixtures.bundle()).toString(Charsets.UTF_8)
        json shouldNotContain "accelerator"
        Fixtures.manifest().manifestId shouldNotBe Fixtures.acceleratorManifest().manifestId
    }

    "a 1.3.0 bundle validates against the 1.3.0 schema, and older bundles still do" {
        schemaErrors("/schemas/evidence-bundle-1.3.0.schema.json", BundleCodec.encodePretty(Fixtures.bundle(Fixtures.acceleratorManifest())))
            .shouldBeEmpty()
        schemaErrors("/schemas/evidence-bundle-1.3.0.schema.json", BundleCodec.encodePretty(Fixtures.bundle(Fixtures.dimmedManifest(), hardware = true)))
            .shouldBeEmpty()
        schemaErrors("/schemas/evidence-bundle-1.3.0.schema.json", resource("/golden/bundle-1.0.0.json")).shouldBeEmpty()
    }

    "the 1.2.0 schema rejects 1.3.0 accelerator fields, so the version bump is real" {
        schemaErrors("/schemas/evidence-bundle-1.2.0.schema.json", BundleCodec.encodePretty(Fixtures.bundle(Fixtures.acceleratorManifest())))
            .isNotEmpty() shouldBe true
    }

    "the 1.1.0 schema rejects 1.2.0 hardware fields, so the version bump is real" {
        schemaErrors("/schemas/evidence-bundle-1.1.0.schema.json", BundleCodec.encodePretty(Fixtures.bundle(Fixtures.dimmedManifest(), hardware = true)))
            .isNotEmpty() shouldBe true
    }
})
