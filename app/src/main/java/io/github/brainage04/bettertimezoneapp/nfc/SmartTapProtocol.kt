package io.github.brainage04.bettertimezoneapp.nfc

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal enum class SmartTapCommandKind {
    SELECT_OSE,
    SELECT_SMART_TAP,
    NEGOTIATE,
    GET_DATA,
    GET_MORE_DATA,
    PUSH_DATA,
    UNKNOWN,
}

internal class SmartTapProtocol(
    private val serviceValue: String?,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private var deviceNonce: ByteArray? = null
    private var deviceKeyPair: KeyPair? = null
    private var devicePublicKeyCompressed: ByteArray? = null
    private var session: Session? = null
    private var terminalNonce: ByteArray? = null
    private var terminalPublicKeyCompressed: ByteArray? = null
    private var collectorId: ByteArray? = null
    private var terminalSignature: ByteArray? = null

    fun process(commandApdu: ByteArray): ByteArray {
        val command = ApduCommand.parse(commandApdu)
        return when (command.kind) {
            SmartTapCommandKind.SELECT_OSE -> selectOseResponse()
            SmartTapCommandKind.SELECT_SMART_TAP -> selectSmartTapResponse()
            SmartTapCommandKind.NEGOTIATE -> negotiateResponse(command.data)
            SmartTapCommandKind.GET_DATA -> getDataResponse(command.data)
            SmartTapCommandKind.PUSH_DATA -> pushDataResponse(command.data)
            SmartTapCommandKind.GET_MORE_DATA -> STATUS_INCORRECT_PARAMETERS
            SmartTapCommandKind.UNKNOWN -> STATUS_INSTRUCTION_NOT_SUPPORTED
        }
    }

    private fun selectOseResponse(): ByteArray {
        ensureDeviceContext()
        val nonce = requireNotNull(deviceNonce)
        val publicKey = requireNotNull(devicePublicKeyCompressed)
        val proprietaryData = tlv(
            byteArrayOf(0x73),
            concatenate(
                tlv(byteArrayOf(0xDF.toByte(), 0x6D), byteArrayOf(0x00, 0x00)),
                tlv(byteArrayOf(0xDF.toByte(), 0x4D), byteArrayOf(0x00, 0x01)),
                tlv(byteArrayOf(0xDF.toByte(), 0x62), byteArrayOf(0x03)),
            ),
        )
        val applicationEntry = tlv(
            byteArrayOf(0x61),
            concatenate(
                tlv(byteArrayOf(0x4F), SMART_TAP_AID),
                tlv(byteArrayOf(0x87.toByte()), byteArrayOf(0x01)),
                proprietaryData,
            ),
        )
        val applicationDirectory = tlv(
            byteArrayOf(0xA5.toByte()),
            tlv(byteArrayOf(0xBF.toByte(), 0x0C), applicationEntry),
        )
        val fileControlInformation = tlv(
            byteArrayOf(0x6F),
            concatenate(
                tlv(byteArrayOf(0x50), "AndroidPay".encodeToByteArray()),
                tlv(byteArrayOf(0xC0.toByte()), byteArrayOf(0x00, 0x01)),
                tlv(
                    byteArrayOf(0xC1.toByte()),
                    byteArrayOf(
                        0xCC.toByte(), 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x80.toByte(), 0x80.toByte(),
                    ),
                ),
                tlv(byteArrayOf(0xC2.toByte()), nonce),
                tlv(byteArrayOf(0xC3.toByte()), publicKey),
                applicationDirectory,
            ),
        )
        return concatenate(fileControlInformation, STATUS_OK)
    }

    private fun selectSmartTapResponse(): ByteArray {
        ensureDeviceContext()
        val handsetNonce = NdefCodec.external(
            type = "mdn",
            id = "mdn".encodeToByteArray(),
            payload = concatenate(byteArrayOf(FORMAT_BINARY), requireNotNull(deviceNonce)),
        )
        return concatenate(
            byteArrayOf(0x00, 0x00, 0x00, 0x01),
            NdefCodec.encode(listOf(handsetNonce)),
            STATUS_OK,
        )
    }

    private fun negotiateResponse(commandData: ByteArray): ByteArray {
        ensureDeviceContext()
        val request = NdefCodec.decode(commandData).singleOrNull { it.hasType("ngr") }
            ?: error("Missing negotiate request")
        require(request.payload.size > 2) { "Invalid negotiate request" }
        val innerRecords = NdefCodec.decode(request.payload.copyOfRange(2, request.payload.size))
        val requestSession = parseSession(innerRecords.firstOrNull { it.hasType("ses") })
        val cryptoParams = innerRecords.firstOrNull { it.hasType("cpr") }
            ?: error("Missing crypto parameters")
        parseCryptoParams(cryptoParams.payload)
        session = requestSession.copy(sequence = requestSession.sequence + 1)

        val responseRecords = listOf(
            sessionRecord(requireNotNull(session)),
            NdefCodec.external("dpk", requireNotNull(devicePublicKeyCompressed)),
        )
        val response = NdefCodec.external("nrs", NdefCodec.encode(responseRecords))
        return concatenate(NdefCodec.encode(listOf(response)), STATUS_OK)
    }

    private fun getDataResponse(commandData: ByteArray): ByteArray {
        val value = serviceValue ?: return STATUS_NO_PASS
        val request = NdefCodec.decode(commandData).singleOrNull { it.hasType("srq") }
            ?: error("Missing service request")
        require(request.payload.size > 2) { "Invalid service request" }
        val innerRecords = NdefCodec.decode(request.payload.copyOfRange(2, request.payload.size))
        val requestSession = parseSession(innerRecords.firstOrNull { it.hasType("ses") })
        val negotiatedSession = requireNotNull(session) { "Secure channel has not been negotiated" }
        require(requestSession.id.contentEquals(negotiatedSession.id)) { "Session id changed" }
        require(requestSession.sequence == negotiatedSession.sequence + 1) { "Invalid session sequence" }
        require(requestRequestsLoyalty(innerRecords)) { "Reader did not request loyalty data" }

        val responseSession = requestSession.copy(sequence = requestSession.sequence + 1)
        session = responseSession
        val encryptedServiceObject = encrypt(createServiceObject(value))
        val responseRecords = listOf(
            sessionRecord(responseSession),
            NdefCodec.external(
                type = "reb",
                payload = concatenate(byteArrayOf(RECORD_BUNDLE_ENCRYPTED), encryptedServiceObject),
            ),
        )
        val response = NdefCodec.external("srs", NdefCodec.encode(responseRecords))
        return concatenate(NdefCodec.encode(listOf(response)), STATUS_OK)
    }

    private fun pushDataResponse(commandData: ByteArray): ByteArray {
        val request = NdefCodec.decode(commandData).singleOrNull { it.hasType("spr") }
            ?: error("Missing push request")
        require(request.payload.size > 2) { "Invalid push request" }
        val requestRecords = NdefCodec.decode(request.payload.copyOfRange(2, request.payload.size))
        val requestSession = parseSession(requestRecords.firstOrNull { it.hasType("ses") })
        val responseSession = requestSession.copy(sequence = requestSession.sequence + 1)
        session = responseSession
        val response = NdefCodec.external(
            "psr",
            NdefCodec.encode(listOf(sessionRecord(responseSession))),
        )
        return concatenate(NdefCodec.encode(listOf(response)), STATUS_OK)
    }

    private fun parseCryptoParams(payload: ByteArray) {
        require(payload.size > CRYPTO_PARAMS_FIXED_LENGTH) { "Invalid crypto parameters" }
        terminalNonce = payload.copyOfRange(0, TERMINAL_NONCE_LENGTH)
        terminalPublicKeyCompressed = payload.copyOfRange(
            TERMINAL_NONCE_LENGTH + AUTH_FLAG_LENGTH,
            TERMINAL_NONCE_LENGTH + AUTH_FLAG_LENGTH + COMPRESSED_KEY_LENGTH,
        )
        val nestedOffset = CRYPTO_PARAMS_FIXED_LENGTH
        val records = NdefCodec.decode(payload.copyOfRange(nestedOffset, payload.size))
        val signatureRecord = records.firstOrNull { it.hasType("sig") }
            ?: error("Missing terminal signature")
        val collectorRecord = records.firstOrNull { it.hasType("cld") }
            ?: error("Missing collector id")
        require(signatureRecord.payload.size > 1 && signatureRecord.payload[0] == FORMAT_BINARY) {
            "Invalid terminal signature"
        }
        require(collectorRecord.payload.size == 5 && collectorRecord.payload[0] == FORMAT_BINARY) {
            "Invalid collector id"
        }
        terminalSignature = signatureRecord.payload.copyOfRange(1, signatureRecord.payload.size)
        collectorId = collectorRecord.payload.copyOfRange(1, collectorRecord.payload.size)
    }

    private fun createServiceObject(value: String): ByteArray {
        val issuer = NdefCodec.external(
            type = "i",
            payload = concatenate(
                byteArrayOf(FORMAT_BINARY, ISSUER_TYPE_MERCHANT),
                requireNotNull(collectorId),
            ),
        )
        val objectId = NdefCodec.external(
            type = "oid",
            payload = concatenate(
                byteArrayOf(FORMAT_BINARY),
                MessageDigest.getInstance("SHA-256")
                    .digest(value.encodeToByteArray())
                    .copyOf(OBJECT_ID_LENGTH),
            ),
        )
        val serviceNumber = NdefCodec.wellKnownText(
            id = "n",
            payload = concatenate(byteArrayOf(FORMAT_UNSPECIFIED), value.encodeToByteArray()),
        )
        val loyalty = NdefCodec.external(
            type = "ly",
            payload = NdefCodec.encode(listOf(objectId, serviceNumber)),
        )
        return NdefCodec.encode(
            listOf(NdefCodec.external("asv", NdefCodec.encode(listOf(issuer, loyalty)))),
        )
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val sharedKey = deriveSharedKey()
        val aesKey = SecretKeySpec(sharedKey.copyOfRange(0, AES_KEY_LENGTH), "AES")
        val hmacKey = sharedKey.copyOfRange(AES_KEY_LENGTH, sharedKey.size)
        val iv = ByteArray(IV_LENGTH).also(secureRandom::nextBytes)
        val counter = concatenate(iv, ByteArray(4))
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, IvParameterSpec(counter))
        val ciphertext = cipher.doFinal(plaintext)
        val authenticatedPayload = concatenate(iv, ciphertext)
        return concatenate(authenticatedPayload, hmacSha256(hmacKey, authenticatedPayload))
    }

    private fun deriveSharedKey(): ByteArray {
        val terminalKeyBytes = requireNotNull(terminalPublicKeyCompressed)
        val deviceKeys = requireNotNull(deviceKeyPair)
        val devicePublic = deviceKeys.public as ECPublicKey
        val terminalPublic = decompressPublicKey(terminalKeyBytes, devicePublic)
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(deviceKeys.private)
        keyAgreement.doPhase(terminalPublic, true)
        val sharedSecret = keyAgreement.generateSecret()
        val info = concatenate(
            requireNotNull(terminalNonce),
            requireNotNull(deviceNonce),
            requireNotNull(collectorId),
            terminalKeyBytes,
            requireNotNull(terminalSignature),
        )
        return hkdfSha256(
            inputKeyMaterial = sharedSecret,
            salt = requireNotNull(devicePublicKeyCompressed),
            info = info,
            length = DERIVED_KEY_LENGTH,
        )
    }

    private fun ensureDeviceContext() {
        if (deviceKeyPair != null) return
        deviceNonce = ByteArray(DEVICE_NONCE_LENGTH).also(secureRandom::nextBytes)
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        deviceKeyPair = generator.generateKeyPair()
        devicePublicKeyCompressed = compressPublicKey(requireNotNull(deviceKeyPair).public as ECPublicKey)
    }

    private fun requestRequestsLoyalty(records: List<NdefRecordValue>): Boolean {
        val serviceList = records.firstOrNull { it.hasType("slr") } ?: return false
        val requestTypes = NdefCodec.decode(serviceList.payload)
            .firstOrNull { it.hasType("str") }
            ?.payload
            ?: return false
        return requestTypes.any { type -> type == SERVICE_TYPE_ALL || type == SERVICE_TYPE_LOYALTY }
    }

    private fun sessionRecord(value: Session): NdefRecordValue = NdefCodec.external(
        type = "ses",
        payload = concatenate(
            value.id,
            byteArrayOf(value.sequence.toByte(), SESSION_STATUS_OK),
        ),
    )

    private fun parseSession(record: NdefRecordValue?): Session {
        val payload = requireNotNull(record) { "Missing session record" }.payload
        require(payload.size == SESSION_LENGTH) { "Invalid session record" }
        require(payload[9] == SESSION_STATUS_OK) { "Reader session is not ready" }
        return Session(payload.copyOfRange(0, 8), payload[8].toInt() and 0xFF)
    }

    private data class Session(val id: ByteArray, val sequence: Int)

    companion object {
        private val OSE_AID = "OSE.VAS.01".encodeToByteArray()
        private val SMART_TAP_AID = hex("A000000476D0000111")
        private val STATUS_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val STATUS_NO_PASS = byteArrayOf(0x90.toByte(), 0x01)
        private val STATUS_INSTRUCTION_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        private val STATUS_INCORRECT_PARAMETERS = byteArrayOf(0x6A, 0x86.toByte())
        private const val FORMAT_UNSPECIFIED: Byte = 0x00
        private const val FORMAT_BINARY: Byte = 0x04
        private const val ISSUER_TYPE_MERCHANT: Byte = 0x01
        private const val SESSION_STATUS_OK: Byte = 0x01
        private const val RECORD_BUNDLE_ENCRYPTED: Byte = 0x01
        private const val SERVICE_TYPE_ALL: Byte = 0x00
        private const val SERVICE_TYPE_LOYALTY: Byte = 0x03
        private const val DEVICE_NONCE_LENGTH = 32
        private const val TERMINAL_NONCE_LENGTH = 32
        private const val AUTH_FLAG_LENGTH = 1
        private const val COMPRESSED_KEY_LENGTH = 33
        private const val KEY_VERSION_LENGTH = 4
        private const val CRYPTO_PARAMS_FIXED_LENGTH =
            TERMINAL_NONCE_LENGTH + AUTH_FLAG_LENGTH + COMPRESSED_KEY_LENGTH + KEY_VERSION_LENGTH
        private const val SESSION_LENGTH = 10
        private const val OBJECT_ID_LENGTH = 8
        private const val IV_LENGTH = 12
        private const val AES_KEY_LENGTH = 16
        private const val DERIVED_KEY_LENGTH = 48
        private val P256_PRIME = BigInteger(
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF",
            16,
        )
        private val P256_A = P256_PRIME.subtract(BigInteger.valueOf(3))
        private val P256_B = BigInteger(
            "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B",
            16,
        )

        fun commandKind(commandApdu: ByteArray): SmartTapCommandKind =
            runCatching { ApduCommand.parse(commandApdu).kind }
                .getOrDefault(SmartTapCommandKind.UNKNOWN)

        fun isSupportedSelect(commandApdu: ByteArray): Boolean = when (commandKind(commandApdu)) {
            SmartTapCommandKind.SELECT_OSE,
            SmartTapCommandKind.SELECT_SMART_TAP,
            -> true

            else -> false
        }

        private fun compressPublicKey(publicKey: ECPublicKey): ByteArray {
            val x = publicKey.w.affineX.toUnsignedFixedLength(32)
            val prefix = if (publicKey.w.affineY.testBit(0)) 0x03 else 0x02
            return concatenate(byteArrayOf(prefix.toByte()), x)
        }

        private fun decompressPublicKey(encoded: ByteArray, curveSource: ECPublicKey): ECPublicKey {
            require(encoded.size == COMPRESSED_KEY_LENGTH) { "Invalid compressed public key" }
            val odd = when (encoded[0].toInt() and 0xFF) {
                0x02 -> false
                0x03 -> true
                else -> error("Invalid compressed public key prefix")
            }
            val x = BigInteger(1, encoded.copyOfRange(1, encoded.size))
            require(x < P256_PRIME) { "Invalid public key coordinate" }
            val ySquared = x.modPow(BigInteger.valueOf(3), P256_PRIME)
                .add(P256_A.multiply(x))
                .add(P256_B)
                .mod(P256_PRIME)
            var y = ySquared.modPow(P256_PRIME.add(BigInteger.ONE).shiftRight(2), P256_PRIME)
            require(y.modPow(BigInteger.valueOf(2), P256_PRIME) == ySquared) {
                "Public key is not on P-256"
            }
            if (y.testBit(0) != odd) y = P256_PRIME.subtract(y)
            val spec = ECPublicKeySpec(ECPoint(x, y), curveSource.params)
            return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
        }

        private fun hkdfSha256(
            inputKeyMaterial: ByteArray,
            salt: ByteArray,
            info: ByteArray,
            length: Int,
        ): ByteArray {
            require(length in 1..(255 * 32)) { "Invalid HKDF output length" }
            val pseudoRandomKey = hmacSha256(salt, inputKeyMaterial)
            val output = ByteArrayOutputStream(length)
            var previous = byteArrayOf()
            var counter = 1
            while (output.size() < length) {
                previous = hmacSha256(
                    pseudoRandomKey,
                    concatenate(previous, info, byteArrayOf(counter.toByte())),
                )
                val remaining = length - output.size()
                output.write(previous, 0, minOf(previous.size, remaining))
                counter++
            }
            return output.toByteArray()
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        private fun tlv(tag: ByteArray, value: ByteArray): ByteArray =
            concatenate(tag, berLength(value.size), value)

        private fun berLength(length: Int): ByteArray = when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
            length <= 0xFFFF -> byteArrayOf(
                0x82.toByte(),
                (length ushr 8).toByte(),
                length.toByte(),
            )

            else -> error("BER-TLV value is too long")
        }

        internal fun concatenate(vararg values: ByteArray): ByteArray {
            val size = values.sumOf(ByteArray::size)
            val result = ByteArray(size)
            var offset = 0
            values.forEach { value ->
                value.copyInto(result, destinationOffset = offset)
                offset += value.size
            }
            return result
        }

        internal fun hex(value: String): ByteArray {
            require(value.length % 2 == 0) { "Hex input must have an even length" }
            return ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        private fun BigInteger.toUnsignedFixedLength(length: Int): ByteArray {
            val encoded = toByteArray()
            val sourceOffset = if (encoded.size > length && encoded[0] == 0.toByte()) 1 else 0
            val sourceLength = encoded.size - sourceOffset
            require(sourceLength <= length) { "Integer does not fit" }
            return ByteArray(length).also { output ->
                encoded.copyInto(
                    destination = output,
                    destinationOffset = length - sourceLength,
                    startIndex = sourceOffset,
                )
            }
        }
    }

    private data class ApduCommand(
        val cla: Int,
        val ins: Int,
        val p1: Int,
        val p2: Int,
        val data: ByteArray,
    ) {
        val kind: SmartTapCommandKind
            get() = when {
                cla == 0x00 && ins == 0xA4 && p1 == 0x04 && data.contentEquals(OSE_AID) ->
                    SmartTapCommandKind.SELECT_OSE

                cla == 0x00 && ins == 0xA4 && p1 == 0x04 && data.contentEquals(SMART_TAP_AID) ->
                    SmartTapCommandKind.SELECT_SMART_TAP

                cla == 0x90 && ins == 0x53 -> SmartTapCommandKind.NEGOTIATE
                cla == 0x90 && ins == 0x50 -> SmartTapCommandKind.GET_DATA
                cla == 0x90 && ins == 0xC0 -> SmartTapCommandKind.GET_MORE_DATA
                cla == 0x90 && ins == 0x52 -> SmartTapCommandKind.PUSH_DATA
                else -> SmartTapCommandKind.UNKNOWN
            }

        companion object {
            fun parse(command: ByteArray): ApduCommand {
                require(command.size >= 4) { "Truncated APDU" }
                val cla = command[0].toInt() and 0xFF
                val ins = command[1].toInt() and 0xFF
                val p1 = command[2].toInt() and 0xFF
                val p2 = command[3].toInt() and 0xFF
                if (command.size <= 5) return ApduCommand(cla, ins, p1, p2, byteArrayOf())

                val firstLength = command[4].toInt() and 0xFF
                val dataOffset: Int
                val dataLength: Int
                if (firstLength != 0) {
                    dataOffset = 5
                    dataLength = firstLength
                } else {
                    require(command.size >= 7) { "Truncated extended APDU" }
                    dataOffset = 7
                    dataLength = ((command[5].toInt() and 0xFF) shl 8) or
                        (command[6].toInt() and 0xFF)
                }
                require(dataOffset + dataLength <= command.size) { "Truncated APDU data" }
                return ApduCommand(
                    cla = cla,
                    ins = ins,
                    p1 = p1,
                    p2 = p2,
                    data = command.copyOfRange(dataOffset, dataOffset + dataLength),
                )
            }
        }
    }
}
