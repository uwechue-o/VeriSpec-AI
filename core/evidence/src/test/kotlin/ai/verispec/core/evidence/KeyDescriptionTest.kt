package ai.verispec.core.evidence

import ai.verispec.core.domain.model.AttestedApp
import ai.verispec.core.domain.model.RootOfTrust
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.model.VerifiedBootState
import ai.verispec.core.evidence.verify.AttestationChainVerifier
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream

/**
 * The root of trust and the app identity sit in the KeyDescription's authorization
 * lists under multi-byte tags ([704] and [709]). These extensions are built byte by
 * byte, so the parser is checked against the ASN.1 layout, not against itself.
 */
class KeyDescriptionTest : StringSpec({

    val verifier = AttestationChainVerifier()
    val signer = Sha256("0ea6f674bf933869fdec0a9aff9f8de191cb870dd4ab9e51ab335797b601a304")
    val challenge = ByteArray(32) { it.toByte() }

    "a TEE key yields its hardware root of trust and the attested app" {
        val parsed = verifier.parseKeyDescription(extension(challenge, rootOfTrust(locked = true, state = 0), appId("ai.verispec.app.debug", signer)))
            .shouldNotBeNull()
        parsed.keymasterSecurityLevel shouldBe 1L
        parsed.challenge shouldBe Sha256.fromBytes(challenge)
        parsed.rootOfTrust shouldBe RootOfTrust(deviceLocked = true, verifiedBootState = VerifiedBootState.VERIFIED)
        parsed.attestedApp shouldBe AttestedApp(listOf("ai.verispec.app.debug"), listOf(signer))
    }

    "an unlocked bootloader and a self-signed OS are read as such" {
        val parsed = verifier.parseKeyDescription(extension(challenge, rootOfTrust(locked = false, state = 1), appId("x", signer)))
            .shouldNotBeNull()
        parsed.rootOfTrust shouldBe RootOfTrust(deviceLocked = false, verifiedBootState = VerifiedBootState.SELF_SIGNED)
        parsed.rootOfTrust!!.isVerified shouldBe false
    }

    "a root of trust in the software-enforced list is ignored" {
        val parsed = verifier.parseKeyDescription(
            extension(challenge, hardware = null, software = listOf(rootOfTrust(locked = true, state = 0), appId("x", signer))),
        ).shouldNotBeNull()
        parsed.rootOfTrust.shouldBeNull()
        parsed.attestedApp.shouldNotBeNull()
    }

    "a malformed app id leaves the rest of the description intact" {
        val broken = tagged(709, der(0x04, byteArrayOf(0x30, 0x00)))
        val parsed = verifier.parseKeyDescription(extension(challenge, rootOfTrust(locked = true, state = 0), broken)).shouldNotBeNull()
        parsed.attestedApp.shouldBeNull()
        parsed.rootOfTrust.shouldNotBeNull()
    }

    "an unknown verified boot state is not guessed" {
        val parsed = verifier.parseKeyDescription(extension(challenge, rootOfTrust(locked = true, state = 7), appId("x", signer)))
            .shouldNotBeNull()
        parsed.rootOfTrust.shouldBeNull()
    }
}) {
    companion object {
        fun der(tag: Int, content: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(tag)
            writeLength(content.size)
            write(content)
        }.toByteArray()

        private fun ByteArrayOutputStream.writeLength(n: Int) = when {
            n < 0x80 -> write(n)
            n < 0x100 -> { write(0x81); write(n) }
            else -> { write(0x82); write(n shr 8); write(n and 0xFF) }
        }

        fun seq(vararg parts: ByteArray) = der(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
        fun set(vararg parts: ByteArray) = der(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
        fun int(v: Int, tag: Int = 0x02) = der(tag, byteArrayOf(v.toByte()))
        fun octets(b: ByteArray) = der(0x04, b)

        /** [n] EXPLICIT, constructed context-specific, in the multi-byte form when n > 30. */
        fun tagged(n: Int, value: ByteArray): ByteArray {
            val id = if (n <= 30) byteArrayOf((0xA0 or n).toByte()) else byteArrayOf(0xBF.toByte(), (0x80 or (n shr 7)).toByte(), (n and 0x7F).toByte())
            val out = ByteArrayOutputStream()
            out.write(id)
            out.writeLength(value.size)
            out.write(value)
            return out.toByteArray()
        }

        fun rootOfTrust(locked: Boolean, state: Int) = tagged(
            704,
            seq(octets(ByteArray(32)), der(0x01, byteArrayOf(if (locked) 0xFF.toByte() else 0)), int(state, tag = 0x0A), octets(ByteArray(32))),
        )

        fun appId(pkg: String, signer: Sha256) = tagged(
            709,
            octets(
                seq(
                    set(seq(octets(pkg.toByteArray()), int(1))),
                    set(octets(signer.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())),
                ),
            ),
        )

        fun extension(challenge: ByteArray, hardware: ByteArray?, app: ByteArray) =
            extension(challenge, hardware, listOf(app))

        /** As getExtensionValue returns it: an OCTET STRING around the KeyDescription. */
        fun extension(challenge: ByteArray, hardware: ByteArray?, software: List<ByteArray>): ByteArray {
            val purpose = tagged(1, set(int(2)))
            val description = seq(
                int(4), int(1, tag = 0x0A), int(41), int(1, tag = 0x0A),
                octets(challenge), octets(ByteArray(0)),
                seq(*software.toTypedArray()),
                seq(purpose, *listOfNotNull(hardware).toTypedArray()),
            )
            return octets(description)
        }
    }
}
