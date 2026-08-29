package io.github.brainage04.bettertimezoneapp.data

import java.time.LocalDate

enum class CardTier(val dailyFreeGames: Int) {
    PLATINUM(2),
    GOLD(1),
}

data class Powercard(
    val number: String,
    val barcodeValue: String,
    val tier: CardTier,
    val balanceCents: Int,
    val tickets: Int,
    val creditExpiry: LocalDate,
    val lastQualifyingReload: LocalDate,
    val originalOrder: Int,
    val detailsVerified: Boolean,
) {
    fun benefitExpiryDate(): LocalDate = lastQualifyingReload.plusDays(BENEFIT_WINDOW_DAYS)

    fun recommendedTopUpDate(leadDays: Int): LocalDate =
        benefitExpiryDate().minusDays(leadDays.coerceIn(0, 91).toLong())

    fun benefitsAreActive(today: LocalDate): Boolean =
        detailsVerified &&
            !today.isBefore(lastQualifyingReload.plusDays(1)) &&
            !today.isAfter(benefitExpiryDate())

    fun topUpIsDue(today: LocalDate, leadDays: Int): Boolean =
        detailsVerified &&
            !today.isBefore(recommendedTopUpDate(leadDays)) &&
            !today.isAfter(benefitExpiryDate())

    companion object {
        const val BENEFIT_WINDOW_DAYS = 92L
    }
}

data class VenueSettings(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
)

data class AppSettings(
    val venue: VenueSettings,
    val remindersEnabled: Boolean,
    val reminderLeadDays: Int,
    val fundedBalanceThresholdCents: Int,
    val audibleReminder: Boolean,
    val vibrateReminder: Boolean,
    val reminderSoundUri: String?,
    val manualCardManagementEnabled: Boolean,
)

data class AppSnapshot(
    val cards: List<Powercard>,
    val usageDate: LocalDate,
    val freeGamesUsed: Map<String, Int>,
    val creditRotationIndex: Int,
    val freeGameOrder: List<String>,
    val settings: AppSettings,
) {
    fun normalized(today: LocalDate): AppSnapshot =
        if (usageDate == today) {
            copy(freeGameOrder = CardQueue.normalizedFreeGameOrder(this))
        } else {
            copy(
                usageDate = today,
                freeGamesUsed = emptyMap(),
                creditRotationIndex = 0,
                freeGameOrder = CardQueue.normalizedFreeGameOrder(this),
            )
        }
}

enum class RecommendationKind {
    FREE_GAME,
    FUNDED_ROTATION,
}

data class CardRecommendation(
    val card: Powercard,
    val kind: RecommendationKind,
    val freeGameNumber: Int? = null,
)

object CardQueue {
    fun normalizedFreeGameOrder(snapshot: AppSnapshot): List<String> {
        val knownNumbers = snapshot.cards.mapTo(mutableSetOf(), Powercard::number)
        val ordered = LinkedHashSet<String>(knownNumbers.size)
        snapshot.freeGameOrder.filterTo(ordered) { it in knownNumbers }
        snapshot.cards
            .sortedWith(compareBy<Powercard>({ it.number.toLongOrNull() ?: Long.MAX_VALUE }, Powercard::number))
            .mapTo(ordered, Powercard::number)
        return ordered.toList()
    }

    fun next(snapshot: AppSnapshot, today: LocalDate): CardRecommendation? {
        val state = snapshot.normalized(today)
        val activeCards = state.cards
            .filter { it.benefitsAreActive(today) }
            .associateBy(Powercard::number)
        val freeOrder = state.freeGameOrder.mapNotNull(activeCards::get)

        freeOrder.firstOrNull { card ->
            state.freeGamesUsed.getOrDefault(card.number, 0) < card.tier.dailyFreeGames
        }?.let { card ->
            return CardRecommendation(
                card = card,
                kind = RecommendationKind.FREE_GAME,
                freeGameNumber = state.freeGamesUsed.getOrDefault(card.number, 0) + 1,
            )
        }

        val threshold = state.settings.fundedBalanceThresholdCents
        val fundedCards = state.cards
            .filter {
                it.detailsVerified &&
                    it.tier == CardTier.GOLD &&
                    it.balanceCents >= threshold
            }
            .sortedWith(
                compareByDescending<Powercard>(Powercard::balanceCents)
                    .thenBy { it.number.toLongOrNull() ?: Long.MAX_VALUE }
                    .thenBy(Powercard::number),
            )
        if (fundedCards.isEmpty()) return null

        val index = Math.floorMod(state.creditRotationIndex, fundedCards.size)
        return CardRecommendation(
            card = fundedCards[index],
            kind = RecommendationKind.FUNDED_ROTATION,
        )
    }

    fun recordNext(snapshot: AppSnapshot, today: LocalDate): AppSnapshot {
        val state = snapshot.normalized(today)
        return when (val recommendation = next(state, today)) {
            null -> state
            else -> when (recommendation.kind) {
                RecommendationKind.FREE_GAME -> state.copy(
                    freeGamesUsed = state.freeGamesUsed + (
                        recommendation.card.number to
                            (state.freeGamesUsed.getOrDefault(recommendation.card.number, 0) + 1)
                        ),
                )

                RecommendationKind.FUNDED_ROTATION -> state.copy(
                    creditRotationIndex = state.creditRotationIndex + 1,
                )
            }
        }
    }

    fun orderedPreview(snapshot: AppSnapshot, today: LocalDate): List<Powercard> {
        val state = snapshot.normalized(today)
        val activeCards = state.cards
            .filter { it.benefitsAreActive(today) }
            .associateBy(Powercard::number)
        return state.freeGameOrder.mapNotNull(activeCards::get)
    }

    fun moveFreeGameCard(
        snapshot: AppSnapshot,
        today: LocalDate,
        cardNumber: String,
        offset: Int,
    ): AppSnapshot {
        if (offset !in setOf(-1, 1)) return snapshot
        val state = snapshot.normalized(today)
        val visibleOrder = orderedPreview(state, today).map(Powercard::number)
        val visibleIndex = visibleOrder.indexOf(cardNumber)
        val targetVisibleIndex = visibleIndex + offset
        if (visibleIndex < 0 || targetVisibleIndex !in visibleOrder.indices) return state

        val order = state.freeGameOrder.toMutableList()
        val currentIndex = order.indexOf(cardNumber)
        val targetIndex = order.indexOf(visibleOrder[targetVisibleIndex])
        if (currentIndex < 0 || targetIndex < 0) return state
        order[currentIndex] = order[targetIndex]
        order[targetIndex] = cardNumber
        return state.copy(freeGameOrder = order)
    }
}
