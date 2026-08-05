# BetterTimezone

BetterTimezone is an offline Android companion for Timezone Powercards. It selects the next card for a daily free game or funded-card rotation and presents the selected card as a barcode or an experimental Android NFC Smart Tap service.

The app is local-first: card data, queue state, reminder settings, and notification state stay on the device. There is no backend or account-sync service.

## Features

- Queue verified Powercards by their daily benefit allowance.
- Support Platinum cards with two daily free games and Gold cards with one.
- Save a custom free-game order and reset daily usage.
- Rotate verified Gold cards above the funded balance threshold by descending balance.
- Import card data after installation instead of shipping real card values in the APK.
- Encrypt the saved card catalog with an Android Keystore-backed AES-GCM key.
- View and edit card tier, game credit, e-Tickets, credit expiry, and last qualifying reload.
- Generate a Code 128 barcode for a selected card.
- Open the official Timezone app, Google Wallet, or Android NFC settings.
- Present the selected card through an experimental direct Smart Tap Host Card Emulation service.
- Schedule venue-entry and periodic top-up reminders with configurable sound and vibration.

## First run and card provisioning

The app starts without cards. Use **Settings → Secure card provisioning → Import card data** and select a private JSON provisioning file. Importing replaces the current card catalog, resets that day's queue state, and enables manual card management.

The provisioning file must contain a `cards` array. Each card requires a numeric card number, a 10-digit barcode value optionally followed by `*` and a numeric suffix, a tier, non-negative balance and ticket counts, and ISO-8601 dates:

```json
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
```

The file is read locally and the imported snapshot is encrypted before it is saved. The source JSON remains the user's responsibility; do not commit it, email it, or leave it in shared storage.

## Queue rules

A card is eligible for the free-game queue when its account details are verified and today is between the day after its last qualifying reload and the end of its 92-day benefit window.

Free games are consumed in the saved queue order. Once all eligible free games are used, verified Gold cards whose balance meets the configured funded-card threshold are selected in a balance-ordered rotation. The default threshold is $5.00.

The queue normalizes state when the date changes. Daily free-game usage and the funded rotation index then start again for the new day.

## NFC presentation

The Present flow displays the exact stored barcode value and can arm the app's HostApduService while the presentation dialog remains open. The Smart Tap implementation handles the OSE and Smart Tap application selections, session negotiation, encrypted service data, and push-data responses.

The service requires an unlocked device. The app labels direct Smart Tap presentation as experimental and not issued or approved by Timezone or Google. The barcode and the official app/Wallet actions remain available as alternatives.

## Venue reminders

Reminders are disabled by default. When enabled, the app can:

- Register an enter geofence for the selected venue.
- Run a WorkManager check every six hours when the device is not low on battery.
- Check whether verified cards have reached their recommended top-up date.
- Post one notification per day when a due card is detected near the venue.

Android may require foreground location, background location, and notification permissions. The default venue is Kingpin Chermside, but its name, coordinates, radius, lead time, and funded-card threshold are editable in Settings.

## Storage protection

The card snapshot is encrypted with AES-GCM. The AES-256 key is generated as a non-exportable key in the Android Keystore. Each write gets a fresh 12-byte nonce, and the encrypted value includes a format version, nonce, and authenticated ciphertext. Associated data binds the ciphertext to its storage purpose.

The NFC override, status message, and status card number use a separate Keystore alias and encrypted state. Older releases stored the snapshot and NFC state in plaintext preferences; the first successful load migrates legacy values to encrypted storage and removes the old preference keys.

This protects data at rest on the device. It cannot hide values from a compromised/rooted device, a debugger attached to a running app, or an APK that contains values needed for offline presentation. Real card values are therefore not included in the built-in catalog or source tree. Keep provisioning files private.

Backup is disabled in the Android manifest. Card numbers, barcode values, and NFC status messages are not logged.

## Architecture

```text
app/src/main/java/io/github/brainage04/bettertimezoneapp/
├── MainActivity.kt
├── data/
│   ├── CardModels.kt       # Card data, queue rules, recommendations
│   ├── CardStore.kt        # Encrypted snapshots and provisioning parser
│   └── SecureStorage.kt    # Android Keystore AES-GCM wrapper
├── nfc/
│   ├── SmartTapProtocol.kt # APDU, NDEF, and Smart Tap crypto flow
│   ├── SmartTapHostApduService.kt
│   ├── NdefCodec.kt
│   └── NfcPresentationState.kt
├── reminders/
│   └── ReminderServices.kt # Geofence, WorkManager, and notifications
└── ui/
    └── App.kt              # Compose navigation and screens
```

The project has two Gradle modules:

- `app` — the Android application with application ID `io.github.brainage04.bettertimezoneapp`.
- `baselineprofile` — the connected-device baseline profile generator.

## Build requirements

- Android Studio with SDK 36 installed.
- JDK 17.
- An Android device or emulator running API 26 or newer.
- NFC hardware is required only for direct NFC presentation.

Build the debug APK:

```bash
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run the unit tests:

```bash
./gradlew test
```

Generate the baseline profile on a connected device:

```bash
./gradlew :baselineprofile:generateBaselineProfile
```

## Tests

Unit tests cover:

- Queue order and funded-card rotation.
- Benefit expiry and top-up dates.
- Unverified and low-balance card handling.
- The empty, unprovisioned default state.
- Smart Tap secure negotiation and card-value delivery.

The project does not require a network service to run.
