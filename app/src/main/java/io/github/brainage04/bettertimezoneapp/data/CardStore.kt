package io.github.brainage04.bettertimezoneapp.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

class CardStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(today: LocalDate = LocalDate.now()): AppSnapshot {
        val stored = encryptedSnapshot()?.let { encoded ->
            SecureStorage.decrypt(SNAPSHOT_KEY_ALIAS, encoded)?.let { json ->
                StoredSnapshot(json = json, isLegacyPlaintext = false)
            }
        } ?: preferences.getString(KEY_LEGACY_SNAPSHOT, null)?.let { json ->
            StoredSnapshot(json = json, isLegacyPlaintext = true)
        } ?: return defaultSnapshot(today)

        val normalized = runCatching { decode(JSONObject(stored.json)).normalized(today) }
            .getOrElse { return defaultSnapshot(today) }
        if (stored.isLegacyPlaintext) {
            runCatching { save(normalized) }
        }
        return normalized
    }

    fun save(snapshot: AppSnapshot) {
        val encrypted = SecureStorage.encrypt(
            SNAPSHOT_KEY_ALIAS,
            encode(snapshot).toString(),
        )
        preferences.edit {
            putString(KEY_ENCRYPTED_SNAPSHOT, encrypted)
            remove(KEY_LEGACY_SNAPSHOT)
        }
    }

    fun importCards(
        provisioningJson: String,
        today: LocalDate = LocalDate.now(),
    ): AppSnapshot = replaceCards(parseProvisioningDocument(provisioningJson), today)

    fun replaceCards(
        cards: List<Powercard>,
        today: LocalDate = LocalDate.now(),
    ): AppSnapshot {
        require(cards.isNotEmpty()) { "At least one card is required" }
        require(cards.map(Powercard::number).toSet().size == cards.size) {
            "Card numbers must be unique"
        }
        val current = load(today)
        return current.copy(
            cards = cards,
            usageDate = today,
            freeGamesUsed = emptyMap(),
            creditRotationIndex = 0,
            freeGameOrder = cards.map(Powercard::number),
            settings = current.settings.copy(manualCardManagementEnabled = true),
        ).normalized(today)
    }

    fun lastNotificationDate(): LocalDate? =
        preferences.getString(KEY_LAST_NOTIFICATION_DATE, null)?.let { encoded ->
            runCatching { LocalDate.parse(encoded) }.getOrNull()
        }

    fun setLastNotificationDate(date: LocalDate) {
        preferences.edit { putString(KEY_LAST_NOTIFICATION_DATE, date.toString()) }
    }

    private fun encryptedSnapshot(): String? =
        preferences.getString(KEY_ENCRYPTED_SNAPSHOT, null)

    private fun encode(snapshot: AppSnapshot): JSONObject = JSONObject().apply {
        put("usageDate", snapshot.usageDate.toString())
        put("creditRotationIndex", snapshot.creditRotationIndex)
        put(
            "freeGameOrder",
            JSONArray().apply { snapshot.freeGameOrder.forEach(::put) },
        )
        put(
            "freeGamesUsed",
            JSONObject().apply {
                snapshot.freeGamesUsed.forEach { (cardNumber, used) -> put(cardNumber, used) }
            },
        )
        put(
            "cards",
            JSONArray().apply {
                snapshot.cards.forEach { card ->
                    put(
                        JSONObject().apply {
                            put("number", card.number)
                            put("barcodeValue", card.barcodeValue)
                            put("tier", card.tier.name)
                            put("balanceCents", card.balanceCents)
                            put("tickets", card.tickets)
                            put("creditExpiry", card.creditExpiry.toString())
                            put("lastQualifyingReload", card.lastQualifyingReload.toString())
                            put("originalOrder", card.originalOrder)
                            put("detailsVerified", card.detailsVerified)
                        },
                    )
                }
            },
        )
        put(
            "settings",
            JSONObject().apply {
                put("remindersEnabled", snapshot.settings.remindersEnabled)
                put("reminderLeadDays", snapshot.settings.reminderLeadDays)
                put("fundedBalanceThresholdCents", snapshot.settings.fundedBalanceThresholdCents)
                put("audibleReminder", snapshot.settings.audibleReminder)
                put("vibrateReminder", snapshot.settings.vibrateReminder)
                put("reminderSoundUri", snapshot.settings.reminderSoundUri ?: JSONObject.NULL)
                put("manualCardManagementEnabled", snapshot.settings.manualCardManagementEnabled)
                put(
                    "venue",
                    JSONObject().apply {
                        put("name", snapshot.settings.venue.name)
                        put("latitude", snapshot.settings.venue.latitude)
                        put("longitude", snapshot.settings.venue.longitude)
                        put("radiusMeters", snapshot.settings.venue.radiusMeters.toDouble())
                    },
                )
            },
        )
    }

    private fun decode(root: JSONObject): AppSnapshot {
        val cardsJson = root.getJSONArray("cards")
        val cards = buildList {
            repeat(cardsJson.length()) { index ->
                val card = cardsJson.getJSONObject(index)
                add(
                    Powercard(
                        number = card.getString("number"),
                        barcodeValue = card.optString("barcodeValue"),
                        tier = CardTier.valueOf(card.getString("tier")),
                        balanceCents = card.getInt("balanceCents"),
                        tickets = card.getInt("tickets"),
                        creditExpiry = LocalDate.parse(card.getString("creditExpiry")),
                        lastQualifyingReload = LocalDate.parse(card.getString("lastQualifyingReload")),
                        originalOrder = card.getInt("originalOrder"),
                        detailsVerified = if (card.has("detailsVerified")) {
                            card.getBoolean("detailsVerified")
                        } else {
                            true
                        },
                    ),
                )
            }
        }
        val usedJson = root.getJSONObject("freeGamesUsed")
        val used = buildMap {
            usedJson.keys().forEach { cardNumber -> put(cardNumber, usedJson.getInt(cardNumber)) }
        }
        val freeGameOrder = buildList {
            root.optJSONArray("freeGameOrder")?.let { order ->
                repeat(order.length()) { index -> add(order.getString(index)) }
            }
        }
        val settingsJson = root.getJSONObject("settings")
        val venueJson = settingsJson.getJSONObject("venue")
        return AppSnapshot(
            cards = cards,
            usageDate = LocalDate.parse(root.getString("usageDate")),
            freeGamesUsed = used,
            creditRotationIndex = root.getInt("creditRotationIndex"),
            freeGameOrder = freeGameOrder,
            settings = AppSettings(
                venue = VenueSettings(
                    name = venueJson.getString("name"),
                    latitude = venueJson.getDouble("latitude"),
                    longitude = venueJson.getDouble("longitude"),
                    radiusMeters = venueJson.getDouble("radiusMeters").toFloat(),
                ),
                remindersEnabled = settingsJson.getBoolean("remindersEnabled"),
                reminderLeadDays = settingsJson.getInt("reminderLeadDays"),
                fundedBalanceThresholdCents = settingsJson.getInt("fundedBalanceThresholdCents"),
                audibleReminder = settingsJson.getBoolean("audibleReminder"),
                vibrateReminder = settingsJson.getBoolean("vibrateReminder"),
                reminderSoundUri = if (settingsJson.isNull("reminderSoundUri")) {
                    null
                } else {
                    settingsJson.getString("reminderSoundUri")
                },
                manualCardManagementEnabled = settingsJson.optBoolean(
                    "manualCardManagementEnabled",
                    false,
                ),
            ),
        )
    }

    private data class StoredSnapshot(
        val json: String,
        val isLegacyPlaintext: Boolean,
    )

    companion object {
        private const val PREFERENCES_NAME = "better_timezone"
        private const val KEY_LEGACY_SNAPSHOT = "snapshot"
        private const val KEY_ENCRYPTED_SNAPSHOT = "snapshot_v1"
        private const val KEY_LAST_NOTIFICATION_DATE = "last_notification_date"
        private const val SNAPSHOT_KEY_ALIAS = "better_timezone_snapshot_v1"
        private const val MAX_IMPORTED_CARDS = 100
        private val CARD_NUMBER_PATTERN = Regex("\\d{1,32}")
        private val BARCODE_PATTERN = Regex("\\d{10}(\\*\\d+)?")

        fun defaultSnapshot(today: LocalDate = LocalDate.now()): AppSnapshot = AppSnapshot(
            cards = emptyList(),
            usageDate = today,
            freeGamesUsed = emptyMap(),
            creditRotationIndex = 0,
            freeGameOrder = emptyList(),
            settings = AppSettings(
                venue = VenueSettings(
                    name = "Kingpin Chermside",
                    latitude = -27.384693,
                    longitude = 153.032058,
                    radiusMeters = 300f,
                ),
                remindersEnabled = false,
                reminderLeadDays = 7,
                fundedBalanceThresholdCents = 500,
                audibleReminder = true,
                vibrateReminder = true,
                reminderSoundUri = null,
                manualCardManagementEnabled = false,
            ),
        )

        internal fun parseProvisioningDocument(json: String): List<Powercard> {
            return try {
                val cardsJson = JSONObject(json).getJSONArray("cards")
                require(cardsJson.length() in 1..MAX_IMPORTED_CARDS)
                val numbers = mutableSetOf<String>()
                val barcodes = mutableSetOf<String>()
                buildList {
                    repeat(cardsJson.length()) { index ->
                        val card = cardsJson.getJSONObject(index)
                        val number = card.optString("number").trim()
                        val barcodeValue = card.optString("barcodeValue").trim()
                        require(CARD_NUMBER_PATTERN.matches(number))
                        require(BARCODE_PATTERN.matches(barcodeValue))
                        require(numbers.add(number))
                        require(barcodes.add(barcodeValue))
                        val tier = CardTier.valueOf(
                            card.optString("tier").trim().uppercase(Locale.US),
                        )
                        val balanceCents = card.getInt("balanceCents")
                        val tickets = card.getInt("tickets")
                        require(balanceCents >= 0)
                        require(tickets >= 0)
                        val originalOrder = card.optInt("originalOrder", index)
                        require(originalOrder >= 0)
                        add(
                            Powercard(
                                number = number,
                                barcodeValue = barcodeValue,
                                tier = tier,
                                balanceCents = balanceCents,
                                tickets = tickets,
                                creditExpiry = LocalDate.parse(card.getString("creditExpiry")),
                                lastQualifyingReload = LocalDate.parse(
                                    card.getString("lastQualifyingReload"),
                                ),
                                originalOrder = originalOrder,
                                detailsVerified = card.optBoolean("detailsVerified", true),
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid card provisioning document")
            }
        }
    }
}
