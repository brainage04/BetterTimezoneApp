package io.github.brainage04.bettertimezoneapp.nfc

import android.content.Context
import androidx.core.content.edit
import io.github.brainage04.bettertimezoneapp.data.SecureStorage
import org.json.JSONObject

internal data class NfcPresentationStatus(
    val cardNumber: String?,
    val message: String,
    val timestampMillis: Long,
)

internal object NfcPresentationState {
    const val ACTION_STATUS_CHANGED =
        "io.github.brainage04.bettertimezoneapp.action.NFC_PRESENTATION_STATUS_CHANGED"

    private const val PREFERENCES_NAME = "better_timezone_nfc"
    private const val KEY_ENCRYPTED_STATE = "state_v1"
    private const val KEY_LEGACY_OVERRIDE_CARD_NUMBER = "override_card_number"
    private const val KEY_LEGACY_STATUS_CARD_NUMBER = "status_card_number"
    private const val KEY_LEGACY_STATUS_MESSAGE = "status_message"
    private const val KEY_LEGACY_STATUS_TIMESTAMP = "status_timestamp"
    private const val STATE_KEY_ALIAS = "better_timezone_nfc_v1"

    fun setOverrideCard(context: Context, cardNumber: String?) {
        val current = loadState(context)
        writeState(context, current.copy(overrideCardNumber = cardNumber))
    }

    fun overrideCardNumber(context: Context): String? =
        loadState(context).overrideCardNumber

    fun recordStatus(context: Context, cardNumber: String?, message: String) {
        val current = loadState(context)
        writeState(
            context,
            current.copy(
                statusCardNumber = cardNumber,
                statusMessage = message,
                statusTimestampMillis = System.currentTimeMillis(),
            ),
        )
    }
    fun loadStatus(context: Context): NfcPresentationStatus? {
        val state = loadState(context)
        return state.statusMessage?.let { message ->
            NfcPresentationStatus(
                cardNumber = state.statusCardNumber,
                message = message,
                timestampMillis = state.statusTimestampMillis,
            )
        }
    }

    private fun loadState(context: Context): StoredState {
        val preferences = preferences(context)
        val encrypted = preferences.getString(KEY_ENCRYPTED_STATE, null)
        if (encrypted != null) {
            SecureStorage.decrypt(STATE_KEY_ALIAS, encrypted)
                ?.let { decodeState(it) }
                ?.let { return it }
        }

        val legacy = StoredState(
            overrideCardNumber = preferences.getString(KEY_LEGACY_OVERRIDE_CARD_NUMBER, null),
            statusCardNumber = preferences.getString(KEY_LEGACY_STATUS_CARD_NUMBER, null),
            statusMessage = preferences.getString(KEY_LEGACY_STATUS_MESSAGE, null),
            statusTimestampMillis = preferences.getLong(KEY_LEGACY_STATUS_TIMESTAMP, 0L),
        )
        if (legacy.hasValue()) writeState(context, legacy)
        return legacy
    }

    private fun writeState(context: Context, state: StoredState) {
        val encrypted = SecureStorage.encrypt(STATE_KEY_ALIAS, encodeState(state).toString())
        preferences(context).edit {
            putString(KEY_ENCRYPTED_STATE, encrypted)
            remove(KEY_LEGACY_OVERRIDE_CARD_NUMBER)
            remove(KEY_LEGACY_STATUS_CARD_NUMBER)
            remove(KEY_LEGACY_STATUS_MESSAGE)
            remove(KEY_LEGACY_STATUS_TIMESTAMP)
        }
    }

    private fun encodeState(state: StoredState): JSONObject = JSONObject().apply {
        put("overrideCardNumber", state.overrideCardNumber ?: JSONObject.NULL)
        put("statusCardNumber", state.statusCardNumber ?: JSONObject.NULL)
        put("statusMessage", state.statusMessage ?: JSONObject.NULL)
        put("statusTimestampMillis", state.statusTimestampMillis)
    }

    private fun decodeState(encoded: String): StoredState? = runCatching {
        val json = JSONObject(encoded)
        StoredState(
            overrideCardNumber = json.optionalString("overrideCardNumber"),
            statusCardNumber = json.optionalString("statusCardNumber"),
            statusMessage = json.optionalString("statusMessage"),
            statusTimestampMillis = json.optLong("statusTimestampMillis", 0L),
        )
    }.getOrNull()

    private fun JSONObject.optionalString(name: String): String? =
        if (isNull(name)) null else optString(name)

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private data class StoredState(
        val overrideCardNumber: String?,
        val statusCardNumber: String?,
        val statusMessage: String?,
        val statusTimestampMillis: Long,
    ) {
        fun hasValue(): Boolean =
            overrideCardNumber != null ||
                statusCardNumber != null ||
                statusMessage != null ||
                statusTimestampMillis != 0L
    }
}
