package app.lightlink

/** Framing: 16-bit preamble, 8-bit UTF-8 length, payload, CRC-8; MSB first. */
object LightProtocol {
    const val MAX_PAYLOAD_BYTES = 48

    fun encode(text: String): BooleanArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.isNotEmpty()) { "اكتب رسالة أولاً." }
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "الحد الأقصى للرسالة $MAX_PAYLOAD_BYTES بايت UTF-8."
        }
        val bytes = ArrayList<Int>()
        bytes.add(0xD3)
        bytes.add(0x91)
        bytes.add(payload.size)
        payload.forEach { bytes.add(it.toInt() and 0xFF) }
        bytes.add(crc8(payload))
        return buildList {
            bytes.forEach { byte ->
                for (shift in 7 downTo 0) add(((byte shr shift) and 1) == 1)
            }
        }.toBooleanArray()
    }

    fun decode(bits: BooleanArray): Result<String> = runCatching {
        require(bits.size % 8 == 0) { "عدد البتات ليس من مضاعفات 8." }
        val bytes = bitsToBytes(bits)
        require(bytes.size >= 5) { "الإطار قصير جدًا." }
        require(bytes[0] == 0xD3 && bytes[1] == 0x91) { "علامة بداية غير صحيحة." }
        val length = bytes[2]
        require(length in 1..MAX_PAYLOAD_BYTES) { "طول الرسالة غير صالح." }
        require(bytes.size == 3 + length + 1) { "طول الإطار لا يطابق البيانات." }
        val payload = bytes.sliceArray(3 until 3 + length)
        require(bytes.last() == crc8(payload)) { "فشل التحقق من سلامة الرسالة (CRC)." }
        payload.toByteArray().toString(Charsets.UTF_8)
    }

    fun crc8(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xFF
                else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    private fun bitsToBytes(bits: BooleanArray): IntArray =
        IntArray(bits.size / 8) { index ->
            var value = 0
            for (i in 0 until 8) value = (value shl 1) or if (bits[index * 8 + i]) 1 else 0
            value
        }
}
