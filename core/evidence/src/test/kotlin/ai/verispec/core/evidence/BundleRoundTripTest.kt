package ai.verispec.core.evidence

import ai.verispec.core.domain.model.RunSchedule
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

class BundleRoundTripTest : StringSpec({

    "bundle survives encode → decode with identical canonical bytes" {
        val bundle = Fixtures.bundle()
        val bytes = BundleCodec.encode(bundle)
        val decoded = BundleCodec.decode(bytes)
        decoded shouldBe bundle
        BundleCodec.encode(decoded) shouldBe bytes
    }

    "pretty and canonical encodings decode to the same bundle" {
        val bundle = Fixtures.bundle()
        BundleCodec.decode(BundleCodec.encodePretty(bundle)) shouldBe BundleCodec.decode(BundleCodec.encode(bundle))
    }

    "bundleId equals the hash of the canonical unsigned bytes after a round trip" {
        val bundle = Fixtures.bundle()
        val decoded = BundleCodec.decode(BundleCodec.encode(bundle))
        Hashing.sha256(EvidenceCanonicalizer.bundleBytes(decoded.unsigned())).hex shouldBe decoded.bundleId.value
    }

    "manifest id is a pure function of manifest content" {
        checkAll(50, Arb.long(), Arb.int(1..500)) { seed, iterations ->
            val a = Fixtures.manifest(seed, iterations)
            val b = Fixtures.manifest(seed, iterations)
            a.manifestId shouldBe b.manifestId
            val c = a.copy(schedule = RunSchedule(iterations + 1))
            Hashing.sha256(EvidenceCanonicalizer.manifestBytes(c)) shouldNotBe Hashing.sha256(EvidenceCanonicalizer.manifestBytes(a))
        }
    }

    "encoded bundle validates against the published JSON Schema" {
        val schemaText = checkNotNull(
            javaClass.getResourceAsStream("/schemas/evidence-bundle-1.0.0.schema.json"),
        ) { "schema resource missing" }.bufferedReader().readText()
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaText)
        val errors = schema.validate(BundleCodec.encodePretty(Fixtures.bundle()), com.networknt.schema.InputFormat.JSON)
        errors.map { it.toString() }.shouldBeEmpty()
    }
})
