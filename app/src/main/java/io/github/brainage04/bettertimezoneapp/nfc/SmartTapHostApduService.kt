package io.github.brainage04.bettertimezoneapp.nfc

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import io.github.brainage04.bettertimezoneapp.data.CardQueue
import io.github.brainage04.bettertimezoneapp.data.CardStore
import io.github.brainage04.bettertimezoneapp.data.Powercard
import java.time.LocalDate

class SmartTapHostApduService : HostApduService() {
    private var protocol: SmartTapProtocol? = null
    private var activeCard: Powercard? = null
    private var sentCardData = false

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val kind = SmartTapProtocol.commandKind(commandApdu)
        if (SmartTapProtocol.isSupportedSelect(commandApdu) && protocol == null) {
            activeCard = selectCard()
            protocol = SmartTapProtocol(activeCard?.smartTapRedemptionValue)
            sentCardData = false
            publishStatus(
                activeCard,
                activeCard?.let { "NFC reader detected. Presenting ${it.number}." }
                    ?: "NFC reader detected, but no card is currently queued.",
            )
        }

        val activeProtocol = protocol ?: SmartTapProtocol(null).also { protocol = it }
        return runCatching { activeProtocol.process(commandApdu) }
            .onSuccess { response -> handleSuccess(kind, response) }
            .getOrElse { error ->
                publishStatus(
                    activeCard,
                    "Smart Tap exchange failed: ${error.message ?: error::class.java.simpleName}",
                )
                STATUS_UNKNOWN_ERROR
            }
    }

    override fun onDeactivated(reason: Int) {
        if (!sentCardData && protocol != null) {
            publishStatus(
                activeCard,
                when (reason) {
                    DEACTIVATION_LINK_LOSS -> "Reader disconnected before card data was sent."
                    DEACTIVATION_DESELECTED -> "Reader selected another NFC service before card data was sent."
                    else -> "NFC exchange ended before card data was sent."
                },
            )
        }
        protocol = null
        activeCard = null
        sentCardData = false
    }

    private fun handleSuccess(kind: SmartTapCommandKind, response: ByteArray) {
        when {
            kind == SmartTapCommandKind.NEGOTIATE && response.endsWithStatus(0x90, 0x00) -> {
                publishStatus(activeCard, "Smart Tap secure channel established with the reader.")
            }

            kind == SmartTapCommandKind.GET_DATA && response.endsWithStatus(0x90, 0x00) -> {
                sentCardData = true
                publishStatus(
                    activeCard,
                    "Card data sent. Advance the queue only if the machine accepted it.",
                )
            }

            kind == SmartTapCommandKind.GET_DATA && response.endsWithStatus(0x90, 0x01) -> {
                publishStatus(activeCard, "The reader requested a card, but none is currently queued.")
            }
        }
    }

    private fun selectCard(): Powercard? {
        val snapshot = CardStore(this).load()
        val overrideNumber = NfcPresentationState.overrideCardNumber(this)
        if (overrideNumber != null) {
            snapshot.cards.firstOrNull {
                it.number == overrideNumber && it.detailsVerified
            }?.let { return it }
        }
        return CardQueue.next(snapshot, LocalDate.now())?.card
    }

    private fun publishStatus(card: Powercard?, message: String) {
        NfcPresentationState.recordStatus(this, card?.number, message)
        sendBroadcast(
            Intent(NfcPresentationState.ACTION_STATUS_CHANGED).setPackage(packageName),
        )
    }

    private fun ByteArray.endsWithStatus(sw1: Int, sw2: Int): Boolean =
        size >= 2 &&
            this[size - 2].toInt() and 0xFF == sw1 &&
            this[size - 1].toInt() and 0xFF == sw2

    companion object {
        private val STATUS_UNKNOWN_ERROR = byteArrayOf(0x6F, 0x00)
    }
}
