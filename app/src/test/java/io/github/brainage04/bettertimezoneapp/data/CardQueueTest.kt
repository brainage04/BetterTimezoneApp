package io.github.brainage04.bettertimezoneapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CardQueueTest {
    private val today = LocalDate.of(2026, 7, 1)

    @Test
    fun queueUsesSavedFreeOrderThenCyclesFundedCardsByDescendingBalance() {
        var snapshot = snapshot(
            card("100", CardTier.PLATINUM, 200, 0),
            card("200", CardTier.GOLD, 100, 1),
            card("300", CardTier.GOLD, 600, 2),
            card("400", CardTier.GOLD, 700, 3),
            card("500", CardTier.GOLD, 800, 4),
        )

        assertNext(snapshot, "100", RecommendationKind.FREE_GAME, 1)
        snapshot = CardQueue.recordNext(snapshot, today)
        assertNext(snapshot, "100", RecommendationKind.FREE_GAME, 2)
        snapshot = CardQueue.recordNext(snapshot, today)
        assertNext(snapshot, "200", RecommendationKind.FREE_GAME, 1)
        snapshot = CardQueue.recordNext(snapshot, today)
        assertNext(snapshot, "300", RecommendationKind.FREE_GAME, 1)
        snapshot = CardQueue.recordNext(snapshot, today)
        assertNext(snapshot, "400", RecommendationKind.FREE_GAME, 1)
        snapshot = CardQueue.recordNext(snapshot, today)
        assertNext(snapshot, "500", RecommendationKind.FREE_GAME, 1)
        snapshot = CardQueue.recordNext(snapshot, today)

        assertNext(snapshot, "500", RecommendationKind.FUNDED_ROTATION, null)
        snapshot = CardQueue.recordNext(snapshot, today)
        assertNext(snapshot, "400", RecommendationKind.FUNDED_ROTATION, null)
        snapshot = CardQueue.recordNext(snapshot, today)
        assertNext(snapshot, "300", RecommendationKind.FUNDED_ROTATION, null)
        snapshot = CardQueue.recordNext(snapshot, today)
        assertNext(snapshot, "500", RecommendationKind.FUNDED_ROTATION, null)
    }

    @Test
    fun defaultFreeOrderIsNumericallyAscending() {
        val snapshot = snapshot(
            card("900", CardTier.GOLD, 100, 0),
            card("100", CardTier.GOLD, 100, 1),
            card("500", CardTier.GOLD, 100, 2),
        ).copy(freeGameOrder = emptyList())

        assertEquals(
            listOf("100", "500", "900"),
            CardQueue.orderedPreview(snapshot, today).map(Powercard::number),
        )
    }

    @Test
    fun customFreeOrderMovesAndPersists() {
        val snapshot = snapshot(
            card("100", CardTier.GOLD, 100, 0),
            card("200", CardTier.GOLD, 100, 1),
            card("300", CardTier.GOLD, 100, 2),
        ).copy(freeGameOrder = listOf("300", "100", "200"))

        val moved = CardQueue.moveFreeGameCard(snapshot, today, "100", -1)

        assertEquals(listOf("100", "300", "200"), moved.freeGameOrder)
        assertEquals(
            listOf("100", "300", "200"),
            CardQueue.orderedPreview(moved, today).map(Powercard::number),
        )
    }

    @Test
    fun expiredAndUnverifiedBenefitsAreSkipped() {
        val snapshot = snapshot(
            card(
                number = "100",
                tier = CardTier.PLATINUM,
                balanceCents = 900,
                order = 0,
                lastReload = today.minusDays(93),
            ),
            card("200", CardTier.GOLD, 100, 1).copy(detailsVerified = false),
        )

        assertNull(CardQueue.next(snapshot, today))
    }

    @Test
    fun lowBalancesNeverEnterFundedRotation() {
        val snapshot = snapshot(
            card("100", CardTier.GOLD, 499, 0),
        ).copy(freeGamesUsed = mapOf("100" to 1))

        assertNull(CardQueue.next(snapshot, today))
    }

    @Test
    fun recommendedTopUpDateUsesNinetyTwoDayWindowAndLeadTime() {
        val card = card(
            number = "100",
            tier = CardTier.GOLD,
            balanceCents = 100,
            order = 0,
            lastReload = LocalDate.of(2026, 5, 25),
        )

        assertEquals(LocalDate.of(2026, 8, 25), card.benefitExpiryDate())
        assertEquals(LocalDate.of(2026, 8, 18), card.recommendedTopUpDate(7))
    }

    @Test
    fun topUpIsDueOnlyWhileActiveBenefitWindowNeedsMaintenance() {
        val card = card(
            number = "100",
            tier = CardTier.GOLD,
            balanceCents = 100,
            order = 0,
            lastReload = LocalDate.of(2026, 5, 25),
        )

        assertFalse(card.topUpIsDue(LocalDate.of(2026, 8, 17), 7))
        assertTrue(card.topUpIsDue(LocalDate.of(2026, 8, 18), 7))
        assertTrue(card.topUpIsDue(LocalDate.of(2026, 8, 25), 7))
        assertFalse(card.topUpIsDue(LocalDate.of(2026, 8, 26), 7))
        assertFalse(
            card.copy(detailsVerified = false).topUpIsDue(LocalDate.of(2026, 8, 18), 7),
        )
    }

    @Test
    fun defaultSnapshotContainsNoShippedCardValues() {
        val snapshot = CardStore.defaultSnapshot(today)

        assertTrue(snapshot.cards.isEmpty())
        assertTrue(snapshot.freeGameOrder.isEmpty())
        assertFalse(snapshot.settings.manualCardManagementEnabled)
    }
    @Test
    fun provisioningParserAcceptsValidatedCardDocument() {
        val cards = CardStore.parseProvisioningDocument(
            """
            {
              "cards": [
                {
                  "number": "123456789",
                  "barcodeValue": "0123456789*123",
                  "tier": "GOLD",
                  "balanceCents": 500,
                  "tickets": 12,
                  "creditExpiry": "2027-08-01",
                  "lastQualifyingReload": "2026-05-25",
                  "originalOrder": 0,
                  "detailsVerified": true
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, cards.size)
        assertEquals("123456789", cards.single().number)
        assertEquals("0123456789*123", cards.single().barcodeValue)
        assertEquals(CardTier.GOLD, cards.single().tier)
    }

    @Test(expected = IllegalArgumentException::class)
    fun provisioningParserRejectsDuplicateCardNumbers() {
        CardStore.parseProvisioningDocument(
            """
            {
              "cards": [
                {
                  "number": "123456789",
                  "barcodeValue": "0123456789*123",
                  "tier": "GOLD",
                  "balanceCents": 500,
                  "tickets": 12,
                  "creditExpiry": "2027-08-01",
                  "lastQualifyingReload": "2026-05-25"
                },
                {
                  "number": "123456789",
                  "barcodeValue": "0123456789*124",
                  "tier": "GOLD",
                  "balanceCents": 500,
                  "tickets": 12,
                  "creditExpiry": "2027-08-01",
                  "lastQualifyingReload": "2026-05-25"
                }
              ]
            }
            """.trimIndent(),
        )
    }


    private fun assertNext(
        snapshot: AppSnapshot,
        number: String,
        kind: RecommendationKind,
        freeGameNumber: Int?,
    ) {
        val recommendation = CardQueue.next(snapshot, today)
        assertEquals(number, recommendation?.card?.number)
        assertEquals(kind, recommendation?.kind)
        assertEquals(freeGameNumber, recommendation?.freeGameNumber)
    }

    private fun snapshot(vararg cards: Powercard): AppSnapshot = AppSnapshot(
        cards = cards.toList(),
        usageDate = today,
        freeGamesUsed = emptyMap(),
        creditRotationIndex = 0,
        freeGameOrder = cards.map(Powercard::number),
        settings = AppSettings(
            venue = VenueSettings("Venue", 0.0, 0.0, 300f),
            remindersEnabled = false,
            reminderLeadDays = 7,
            fundedBalanceThresholdCents = 500,
            audibleReminder = true,
            vibrateReminder = true,
            reminderSoundUri = null,
            manualCardManagementEnabled = false,
        ),
    )

    private fun card(
        number: String,
        tier: CardTier,
        balanceCents: Int,
        order: Int,
        lastReload: LocalDate = today.minusDays(1),
    ): Powercard = Powercard(
        number = number,
        barcodeValue = "0$number",
        tier = tier,
        balanceCents = balanceCents,
        tickets = 0,
        creditExpiry = today.plusYears(1),
        lastQualifyingReload = lastReload,
        originalOrder = order,
        detailsVerified = true,
    )
}
