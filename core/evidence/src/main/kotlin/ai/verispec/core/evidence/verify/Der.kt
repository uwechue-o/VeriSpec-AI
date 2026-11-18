package ai.verispec.core.evidence.verify

/**
 * Minimal DER reader: enough to walk the Android Key Attestation extension
 * (KeyDescription) without pulling a full ASN.1 library onto the device.
 * Rejects indefinite lengths and anything malformed.
 */
class Der(private val bytes: ByteArray, private var pos: Int = 0, private val end: Int = bytes.size) {

    class Tlv(val tag: Int, val content: ByteArray) {
        val isSequence: Boolean get() = tag == TAG_SEQUENCE
        val isOctetString: Boolean get() = tag == TAG_OCTET_STRING
        fun children(): List<Tlv> = Der(content).readAll()
        fun asInt(): Long {
            require(tag == TAG_INTEGER || tag == TAG_ENUMERATED) { "Expected INTEGER/ENUMERATED, got tag $tag" }
            require(content.size in 1..8) { "Integer too wide: ${content.size} bytes" }
            var v = 0L
            for (b in content) v = (v shl 8) or (b.toLong() and 0xFF)
            // sign-extend
            val shift = 64 - content.size * 8
            return (v shl shift) shr shift
        }
    }

    fun hasMore(): Boolean = pos < end

    fun read(): Tlv {
        require(pos < end) { "Unexpected end of DER data" }
        val tag = bytes[pos++].toInt() and 0xFF
        require((tag and 0x1F) != 0x1F) { "Multi-byte tags not supported" }
        val length = readLength()
        require(pos + length <= end) { "DER length $length exceeds available bytes" }
        val content = bytes.copyOfRange(pos, pos + length)
        pos += length
        return Tlv(tag, content)
    }

    fun readAll(): List<Tlv> {
        val out = ArrayList<Tlv>()
        while (hasMore()) out += read()
        return out
    }

    private fun readLength(): Int {
        val first = bytes[pos++].toInt() and 0xFF
        if (first < 0x80) return first
        val count = first and 0x7F
        require(count in 1..4) { "Unsupported DER length encoding (count=$count)" }
        var len = 0
        repeat(count) { len = (len shl 8) or (bytes[pos++].toInt() and 0xFF) }
        require(len >= 0) { "Negative DER length" }
        return len
    }

    companion object {
        const val TAG_INTEGER = 0x02
        const val TAG_OCTET_STRING = 0x04
        const val TAG_ENUMERATED = 0x0A
        const val TAG_SEQUENCE = 0x30
    }
}
