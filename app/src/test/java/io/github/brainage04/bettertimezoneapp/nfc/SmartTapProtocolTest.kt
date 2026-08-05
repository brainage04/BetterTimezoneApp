package io.github.brainage04.bettertimezoneapp.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SmartTapProtocolTest {
    @Test
    fun secureGetDataFlowReturnsSelectedCardValue() {
        val protocol = SmartTapProtocol("0242425603")
        val selectResponse = protocol.process(
            command(0x00, 0xA4, 0x04, 0x00, SmartTapProtocol.hex("A000000476D0000111")),
        )
        assertStatus(selectResponse, 0x90, 0x00)
        val selectPayload = selectResponse.copyOfRange(0, selectResponse.size - 2)
        val mobileNonce = NdefCodec.decode(selectPayload.copyOfRange(4, selectPayload.size))
            .single { it.hasType("mdn") }
            .payload
            .copyOfRange(1, 33)

        val terminalKeys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val terminalPublicCompressed = compress(terminalKeys.public as ECPublicKey)
        val terminalNonce = ByteArray(32) { index -> (index + 1).toByte() }
        val collectorId = byteArrayOf(0x01, 0x33, 0xEE.toByte(), 0x80.toByte())
        val signature = ByteArray(71) { index -> (index * 3 + 1).toByte() }
        val sessionId = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x7F)
        val collectorRecord = NdefCodec.external(
            "cld",
            SmartTapProtocol.concatenate(byteArrayOf(0x04), collectorId),
        )
        val signatureRecord = NdefCodec.external(
            "sig",
            SmartTapProtocol.concatenate(byteArrayOf(0x04), signature),
        )
        val cryptoParams = NdefCodec.external(
            "cpr",
            SmartTapProtocol.concatenate(
                terminalNonce,
                byteArrayOf(0x01),
                terminalPublicCompressed,
                byteArrayOf(0x00, 0x00, 0x00, 0x01),
                NdefCodec.encode(listOf(signatureRecord, collectorRecord)),
            ),
        )
        val negotiateSession = sessionRecord(sessionId, 1)
        val negotiateRequest = NdefCodec.external(
            "ngr",
            SmartTapProtocol.concatenate(
                byteArrayOf(0x00, 0x01),
                NdefCodec.encode(listOf(negotiateSession, cryptoParams)),
            ),
        )
        val negotiateResponse = protocol.process(
            command(0x90, 0x53, 0x00, 0x00, NdefCodec.encode(listOf(negotiateRequest))),
        )
        assertStatus(negotiateResponse, 0x90, 0x00)
        val negotiateOuter = NdefCodec.decode(withoutStatus(negotiateResponse))
            .single { it.hasType("nrs") }
        val negotiateRecords = NdefCodec.decode(negotiateOuter.payload)
        val mobilePublicCompressed = negotiateRecords.single { it.hasType("dpk") }.payload
        assertEquals(2, negotiateRecords.single { it.hasType("ses") }.payload[8].toInt())

        val merchant = NdefCodec.external(
            "mer",
            NdefCodec.encode(listOf(collectorRecord)),
        )
        val serviceTypes = NdefCodec.external("str", byteArrayOf(0x03))
        val serviceList = NdefCodec.external(
            "slr",
            NdefCodec.encode(listOf(serviceTypes)),
        )
        val getDataRequest = NdefCodec.external(
            "srq",
            SmartTapProtocol.concatenate(
                byteArrayOf(0x00, 0x01),
                NdefCodec.encode(listOf(sessionRecord(sessionId, 3), merchant, serviceList)),
            ),
        )
        val getDataResponse = protocol.process(
            command(0x90, 0x50, 0x00, 0x00, NdefCodec.encode(listOf(getDataRequest))),
        )
        assertStatus(getDataResponse, 0x90, 0x00)

        val serviceResponse = NdefCodec.decode(withoutStatus(getDataResponse))
            .single { it.hasType("srs") }
        val serviceRecords = NdefCodec.decode(serviceResponse.payload)
        assertEquals(4, serviceRecords.single { it.hasType("ses") }.payload[8].toInt())
        val recordBundle = serviceRecords.single { it.hasType("reb") }.payload
        assertEquals(0x01, recordBundle[0].toInt())
        val encryptedPayload = recordBundle.copyOfRange(1, recordBundle.size)

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(terminalKeys.private)
        agreement.doPhase(decompress(mobilePublicCompressed, terminalKeys.public as ECPublicKey), true)
        val sharedSecret = agreement.generateSecret()
        val info = SmartTapProtocol.concatenate(
            terminalNonce,
            mobileNonce,
            collectorId,
            terminalPublicCompressed,
            signature,
        )
        val sharedKey = hkdf(sharedSecret, mobilePublicCompressed, info, 48)
        val iv = encryptedPayload.copyOfRange(0, 12)
        val ciphertext = encryptedPayload.copyOfRange(12, encryptedPayload.size - 32)
        val receivedHmac = encryptedPayload.copyOfRange(encryptedPayload.size - 32, encryptedPayload.size)
        assertArrayEquals(receivedHmac, hmac(sharedKey.copyOfRange(16, 48), iv + ciphertext))
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sharedKey.copyOfRange(0, 16), "AES"),
            IvParameterSpec(iv + ByteArray(4)),
        )
        val plaintext = cipher.doFinal(ciphertext)
        val serviceValue = extractServiceValue(plaintext)

        assertEquals("0242425603", serviceValue)
    }

    @Test
    fun oseSelectionAdvertisesAndroidPayAndSmartTapTwo() {
        val response = SmartTapProtocol(null).process(
            command(0x00, 0xA4, 0x04, 0x00, "OSE.VAS.01".encodeToByteArray()),
        )

        assertStatus(response, 0x90, 0x00)
        assertTrue(response.containsSequence("AndroidPay".encodeToByteArray()))
        assertTrue(response.containsSequence(SmartTapProtocol.hex("A000000476D0000111")))
    }

    private fun extractServiceValue(plaintext: ByteArray): String {
        val serviceValue = NdefCodec.decode(plaintext).single { it.hasType("asv") }
        val serviceRecords = NdefCodec.decode(serviceValue.payload)
        val issuer = serviceRecords.single { it.hasType("i") }
        assertEquals(6, issuer.payload.size)
        assertEquals(0x04, issuer.payload[0].toInt())
        assertEquals(0x01, issuer.payload[1].toInt())
        val loyalty = serviceRecords.single { it.hasType("ly") }
        val loyaltyRecords = NdefCodec.decode(loyalty.payload)
        val objectId = loyaltyRecords.single { it.hasType("oid") }
        assertEquals(9, objectId.payload.size)
        assertEquals(0x04, objectId.payload[0].toInt())
        val number = loyaltyRecords.single {
            it.id.contentEquals("n".encodeToByteArray())
        }
        assertEquals(0x00, number.payload[0].toInt())
        return number.payload.copyOfRange(1, number.payload.size).decodeToString()
    }

    private fun sessionRecord(id: ByteArray, sequence: Int): NdefRecordValue =
        NdefCodec.external("ses", id + byteArrayOf(sequence.toByte(), 0x01))

    private fun command(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray): ByteArray {
        require(data.size <= 255)
        return byteArrayOf(
            cla.toByte(),
            ins.toByte(),
            p1.toByte(),
            p2.toByte(),
            data.size.toByte(),
        ) + data + byteArrayOf(0x00)
    }

    private fun withoutStatus(response: ByteArray): ByteArray =
        response.copyOfRange(0, response.size - 2)

    private fun assertStatus(response: ByteArray, sw1: Int, sw2: Int) {
        assertTrue(response.size >= 2)
        assertEquals(sw1, response[response.size - 2].toInt() and 0xFF)
        assertEquals(sw2, response[response.size - 1].toInt() and 0xFF)
    }

    private fun compress(publicKey: ECPublicKey): ByteArray {
        val x = fixedLength(publicKey.w.affineX, 32)
        return byteArrayOf(if (publicKey.w.affineY.testBit(0)) 0x03 else 0x02) + x
    }

    private fun decompress(encoded: ByteArray, curveSource: ECPublicKey): ECPublicKey {
        val odd = encoded[0].toInt() == 0x03
        val x = BigInteger(1, encoded.copyOfRange(1, encoded.size))
        val ySquared = x.modPow(BigInteger.valueOf(3), P256_PRIME)
            .add(P256_PRIME.subtract(BigInteger.valueOf(3)).multiply(x))
            .add(P256_B)
            .mod(P256_PRIME)
        var y = ySquared.modPow(P256_PRIME.add(BigInteger.ONE).shiftRight(2), P256_PRIME)
        if (y.testBit(0) != odd) y = P256_PRIME.subtract(y)
        return KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(x, y), curveSource.params),
        ) as ECPublicKey
    }

    private fun fixedLength(value: BigInteger, length: Int): ByteArray {
        val encoded = value.toByteArray()
        val offset = if (encoded.size > length) 1 else 0
        return ByteArray(length).also {
            encoded.copyInto(it, length - (encoded.size - offset), offset)
        }
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val output = ByteArrayOutputStream(length)
        var previous = byteArrayOf()
        var counter = 1
        while (output.size() < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            output.write(previous, 0, minOf(previous.size, length - output.size()))
            counter++
        }
        return output.toByteArray()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun ByteArray.containsSequence(expected: ByteArray): Boolean =
        indices.any { start ->
            start + expected.size <= size &&
                expected.indices.all { offset -> this[start + offset] == expected[offset] }
        }

    companion object {
        private val P256_PRIME = BigInteger(
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF",
            16,
        )
        private val P256_B = BigInteger(
            "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B",
            16,
        )
    }
}
