package io.github.brainage04.bettertimezoneapp.nfc

import java.io.ByteArrayOutputStream

internal data class NdefRecordValue(
    val tnf: Int,
    val type: ByteArray,
    val id: ByteArray = byteArrayOf(),
    val payload: ByteArray = byteArrayOf(),
) {
    fun hasType(value: String): Boolean = type.contentEquals(value.encodeToByteArray())
}

internal object NdefCodec {
    const val TNF_WELL_KNOWN = 0x01
    const val TNF_EXTERNAL = 0x04

    fun external(
        type: String,
        payload: ByteArray,
        id: ByteArray = byteArrayOf(),
    ): NdefRecordValue = NdefRecordValue(
        tnf = TNF_EXTERNAL,
        type = type.encodeToByteArray(),
        id = id,
        payload = payload,
    )

    fun wellKnownText(id: String, payload: ByteArray): NdefRecordValue = NdefRecordValue(
        tnf = TNF_WELL_KNOWN,
        type = byteArrayOf('T'.code.toByte()),
        id = id.encodeToByteArray(),
        payload = payload,
    )

    fun encode(records: List<NdefRecordValue>): ByteArray {
        require(records.isNotEmpty()) { "An NDEF message needs at least one record" }
        val output = ByteArrayOutputStream()
        records.forEachIndexed { index, record ->
            require(record.tnf in 0..7) { "Invalid NDEF TNF" }
            require(record.type.size <= UByte.MAX_VALUE.toInt()) { "NDEF type is too long" }
            require(record.id.size <= UByte.MAX_VALUE.toInt()) { "NDEF id is too long" }

            val shortRecord = record.payload.size <= UByte.MAX_VALUE.toInt()
            var header = record.tnf
            if (index == 0) header = header or 0x80
            if (index == records.lastIndex) header = header or 0x40
            if (shortRecord) header = header or 0x10
            if (record.id.isNotEmpty()) header = header or 0x08

            output.write(header)
            output.write(record.type.size)
            if (shortRecord) {
                output.write(record.payload.size)
            } else {
                output.writeInt(record.payload.size)
            }
            if (record.id.isNotEmpty()) output.write(record.id.size)
            output.write(record.type)
            output.write(record.id)
            output.write(record.payload)
        }
        return output.toByteArray()
    }

    fun decode(message: ByteArray): List<NdefRecordValue> {
        require(message.isNotEmpty()) { "Empty NDEF message" }
        var offset = 0
        var first = true
        var finished = false
        val records = mutableListOf<NdefRecordValue>()

        while (offset < message.size && !finished) {
            val header = message.readUnsigned(offset++)
            val messageBegin = header and 0x80 != 0
            val messageEnd = header and 0x40 != 0
            val chunked = header and 0x20 != 0
            val shortRecord = header and 0x10 != 0
            val hasId = header and 0x08 != 0
            require(messageBegin == first) { "Invalid NDEF message boundary" }
            require(!chunked) { "Chunked NDEF records are unsupported" }

            val typeLength = message.readUnsigned(offset++)
            val payloadLength = if (shortRecord) {
                message.readUnsigned(offset++).toLong()
            } else {
                message.readUnsignedInt(offset).also { offset += 4 }
            }
            val idLength = if (hasId) message.readUnsigned(offset++) else 0
            require(payloadLength <= Int.MAX_VALUE) { "NDEF payload is too large" }
            val recordEnd = offset.toLong() + typeLength + idLength + payloadLength
            require(recordEnd <= message.size) { "Truncated NDEF record" }

            val type = message.copyOfRange(offset, offset + typeLength)
            offset += typeLength
            val id = message.copyOfRange(offset, offset + idLength)
            offset += idLength
            val payloadSize = payloadLength.toInt()
            val payload = message.copyOfRange(offset, offset + payloadSize)
            offset += payloadSize
            records += NdefRecordValue(header and 0x07, type, id, payload)
            first = false
            finished = messageEnd
        }

        require(finished && offset == message.size) { "Invalid NDEF message length" }
        return records
    }

    private fun ByteArray.readUnsigned(index: Int): Int {
        require(index in indices) { "Truncated NDEF message" }
        return this[index].toInt() and 0xFF
    }

    private fun ByteArray.readUnsignedInt(index: Int): Long {
        require(index >= 0 && index + 4 <= size) { "Truncated NDEF message" }
        return (readUnsigned(index).toLong() shl 24) or
            (readUnsigned(index + 1).toLong() shl 16) or
            (readUnsigned(index + 2).toLong() shl 8) or
            readUnsigned(index + 3).toLong()
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }
}
