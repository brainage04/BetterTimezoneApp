package io.github.brainage04.bettertimezoneapp.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.IntentFilter
import android.graphics.Bitmap
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import io.github.brainage04.bettertimezoneapp.data.AppSettings
import io.github.brainage04.bettertimezoneapp.data.AppSnapshot
import io.github.brainage04.bettertimezoneapp.data.CardQueue
import io.github.brainage04.bettertimezoneapp.data.CardRecommendation
import io.github.brainage04.bettertimezoneapp.data.CardStore
import io.github.brainage04.bettertimezoneapp.data.CardTier
import io.github.brainage04.bettertimezoneapp.data.Powercard
import io.github.brainage04.bettertimezoneapp.data.RecommendationKind
import io.github.brainage04.bettertimezoneapp.data.VenueSettings
import io.github.brainage04.bettertimezoneapp.nfc.NfcPresentationState
import io.github.brainage04.bettertimezoneapp.nfc.NfcPresentationStatus
import io.github.brainage04.bettertimezoneapp.nfc.SmartTapHostApduService
import io.github.brainage04.bettertimezoneapp.reminders.ReminderNotifier
import io.github.brainage04.bettertimezoneapp.reminders.VenueGeofenceRegistrar
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

private enum class AppSection(val label: String, val shortLabel: String) {
    QUEUE("Queue", "Q"),
    CARDS("Cards", "C"),
    SETTINGS("Settings", "S"),
}

private val displayDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember { CardStore(context) }
    var snapshot by remember { mutableStateOf(store.load()) }
    var section by remember { mutableStateOf(AppSection.QUEUE) }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    fun persist(updated: AppSnapshot) {
        val normalized = updated.normalized(LocalDate.now())
        snapshot = normalized
        store.save(normalized)
    }

    fun openApplicationSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri(),
            ),
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRefresh++
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            openApplicationSettings()
        } else {
            VenueGeofenceRegistrar.sync(context, snapshot)
        }
    }
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedUri = result.data?.pickedRingtoneUri()
            persist(
                snapshot.copy(
                    settings = snapshot.settings.copy(reminderSoundUri = selectedUri?.toString()),
                ),
            )
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            val json = context.contentResolver.openInputStream(uri)?.use(::readUtf8Limited)
                ?: error("Unable to read the selected file")
            store.importCards(json)
        }
        result.onSuccess { imported ->
            persist(imported)
            importMessage = "Imported ${imported.cards.size} cards. Values are encrypted on this device."
        }.onFailure {
            importMessage = "Import failed. Select a valid JSON card file."
        }
    }

    fun requestReminderPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRefresh++
                VenueGeofenceRegistrar.sync(context, snapshot)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(snapshot.settings.remindersEnabled) {
        VenueGeofenceRegistrar.sync(context, snapshot)
    }
    LaunchedEffect(snapshot.settings.manualCardManagementEnabled) {
        if (!snapshot.settings.manualCardManagementEnabled && section == AppSection.CARDS) {
            section = AppSection.SETTINGS
        }
    }

    val visibleSections = if (snapshot.settings.manualCardManagementEnabled) {
        AppSection.entries
    } else {
        AppSection.entries.filterNot { it == AppSection.CARDS }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BetterTimezone", fontWeight = FontWeight.Bold)
                        Text(
                            "Offline Powercard companion",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                visibleSections.forEach { item ->
                    NavigationBarItem(
                        selected = section == item,
                        onClick = { section = item },
                        icon = { Text(item.shortLabel, fontWeight = FontWeight.Bold) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (section) {
            AppSection.QUEUE -> QueueScreen(
                snapshot = snapshot,
                modifier = Modifier.padding(innerPadding),
                onRecordTap = {
                    persist(CardQueue.recordNext(snapshot, LocalDate.now()))
                },
                onResetDay = {
                    persist(
                        snapshot.copy(
                            usageDate = LocalDate.now(),
                            freeGamesUsed = emptyMap(),
                            creditRotationIndex = 0,
                        ),
                    )
                },
                onMoveCard = { cardNumber, offset ->
                    persist(
                        CardQueue.moveFreeGameCard(
                            snapshot = snapshot,
                            today = LocalDate.now(),
                            cardNumber = cardNumber,
                            offset = offset,
                        ),
                    )
                },
            )

            AppSection.CARDS -> CardsScreen(
                snapshot = snapshot,
                modifier = Modifier.padding(innerPadding),
                onUpdateCard = { updatedCard ->
                    persist(
                        snapshot.copy(
                            cards = snapshot.cards.map { card ->
                                if (card.number == updatedCard.number) updatedCard else card
                            },
                        ),
                    )
                },
            )

            AppSection.SETTINGS -> SettingsScreen(
                snapshot = snapshot,
                modifier = Modifier.padding(innerPadding),
                permissionRefresh = permissionRefresh,
                onSaveSettings = { updatedSettings ->
                    val updated = snapshot.copy(settings = updatedSettings)
                    persist(updated)
                    VenueGeofenceRegistrar.sync(context, updated)
                },
                onToggleManualManagement = { enabled ->
                    persist(
                        snapshot.copy(
                            settings = snapshot.settings.copy(
                                manualCardManagementEnabled = enabled,
                            ),
                        ),
                    )
                },
                onToggleReminders = { enabled ->
                    val updated = snapshot.copy(
                        settings = snapshot.settings.copy(remindersEnabled = enabled),
                    )
                    persist(updated)
                    if (enabled) requestReminderPermissions() else VenueGeofenceRegistrar.sync(context, updated)
                },
                onRequestPermissions = ::requestReminderPermissions,
                onOpenSettings = ::openApplicationSettings,
                onPickSound = {
                    ringtoneLauncher.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                snapshot.settings.reminderSoundUri?.toUri(),
                            )
                        },
                    )
                },
                onTestReminder = { ReminderNotifier.notifyIfDue(context, forceTest = true) },
                onImportCards = {
                    importLauncher.launch(arrayOf("application/json", "text/plain"))
                },
                importMessage = importMessage,
            )
        }
    }
}

@Composable
private fun QueueScreen(
    snapshot: AppSnapshot,
    modifier: Modifier,
    onRecordTap: () -> Unit,
    onResetDay: () -> Unit,
    onMoveCard: (String, Int) -> Unit,
) {
    val today = LocalDate.now()
    val state = snapshot.normalized(today)
    val recommendation = CardQueue.next(state, today)
    val preview = CardQueue.orderedPreview(state, today)
    val unverifiedCount = state.cards.count { !it.detailsVerified }
    var reorderingCardNumber by remember { mutableStateOf<String?>(null) }
    var nfcPresentationOpen by remember { mutableStateOf(false) }

    LaunchedEffect(nfcPresentationOpen, recommendation) {
        if (nfcPresentationOpen && recommendation == null) nfcPresentationOpen = false
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Next tap", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Free games follow your saved order. Funded Gold cards then cycle automatically from highest balance to lowest. Open Present, tap the reader, then advance only after the machine accepts the card.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            if (recommendation == null) {
                if (state.cards.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No cards have been provisioned", fontWeight = FontWeight.Bold)
                            Text(
                                "Import a JSON card file from Settings. Card values are encrypted with the Android Keystore on this device.",
                            )
                        }
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No card is currently queued", fontWeight = FontWeight.Bold)
                            Text(
                                "All verified free games are marked used and no Gold card meets the funded threshold of ${formatMoney(state.settings.fundedBalanceThresholdCents)}.",
                            )
                        }
                    }
                }
            } else {
                RecommendationCard(
                    recommendation = recommendation,
                    onRecordTap = onRecordTap,
                    onPresent = { nfcPresentationOpen = true },
                )
            }
        }
        item {
            Text(
                "Present arms experimental direct NFC for the queued card and also shows its exact photographed barcode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Today's free-game queue",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onResetDay) {
                    Text("Reset", maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
            Text(
                "Hold a card to show move controls. The custom order is saved.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (unverifiedCount > 0) {
                Text(
                    "$unverifiedCount photo-imported cards are excluded until their account details are verified in manual card management.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (preview.isEmpty()) {
            item { Text("No verified card currently has an active daily benefit window.") }
        } else {
            itemsIndexed(preview, key = { _, card -> card.number }) { index, card ->
                val used = state.freeGamesUsed.getOrDefault(card.number, 0)
                QueueRow(
                    card = card,
                    used = used,
                    showMoveControls = reorderingCardNumber == card.number,
                    canMoveUp = index > 0,
                    canMoveDown = index < preview.lastIndex,
                    onLongPress = { reorderingCardNumber = card.number },
                    onMoveUp = { onMoveCard(card.number, -1) },
                    onMoveDown = { onMoveCard(card.number, 1) },
                )
            }
        }
    }
    if (nfcPresentationOpen && recommendation != null) {
        PresentPowercardDialog(
            card = recommendation.card,
            onDismiss = { nfcPresentationOpen = false },
            queueInstruction = when (recommendation.kind) {
                RecommendationKind.FREE_GAME ->
                    "Daily free game ${recommendation.freeGameNumber} of ${recommendation.card.tier.dailyFreeGames}"
                RecommendationKind.FUNDED_ROTATION ->
                    "Funded cycle • ${formatMoney(recommendation.card.balanceCents)}"
            },
            onAcceptedAndNext = onRecordTap,
        )
    }
}

@Composable
private fun RecommendationCard(
    recommendation: CardRecommendation,
    onRecordTap: () -> Unit,
    onPresent: () -> Unit,
) {
    val card = recommendation.card
    val background = if (card.tier == CardTier.PLATINUM) Color(0xFF30373B) else Color(0xFFE4B84E)
    val foreground = if (card.tier == CardTier.PLATINUM) Color.White else Color(0xFF241B00)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(card.tier.name.lowercase().replaceFirstChar(Char::uppercase), fontWeight = FontWeight.Bold)
            Text(card.number, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                when (recommendation.kind) {
                    RecommendationKind.FREE_GAME -> "Daily free game ${recommendation.freeGameNumber} of ${card.tier.dailyFreeGames}"
                    RecommendationKind.FUNDED_ROTATION -> "Funded cycle • ${formatMoney(card.balanceCents)}"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onRecordTap, modifier = Modifier.weight(1f)) {
                    Text("Advance", maxLines = 1, overflow = TextOverflow.Clip)
                }
                OutlinedButton(onClick = onPresent, modifier = Modifier.weight(1f)) {
                    Text("Present", maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    card: Powercard,
    used: Int,
    showMoveControls: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onLongPress: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(card.number, fontWeight = FontWeight.SemiBold)
                    Text("${card.tier.name} • ${formatMoney(card.balanceCents)}")
                }
                Text("$used / ${card.tier.dailyFreeGames} used", textAlign = TextAlign.End)
            }
            if (showMoveControls) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Text("Move up", maxLines = 1)
                    }
                    OutlinedButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Text("Move down", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardsScreen(
    snapshot: AppSnapshot,
    modifier: Modifier,
    onUpdateCard: (Powercard) -> Unit,
) {
    var editing by remember { mutableStateOf<Powercard?>(null) }
    var presenting by remember { mutableStateOf<Powercard?>(null) }
    val today = LocalDate.now()
    val verifiedCards = snapshot.cards.filter(Powercard::detailsVerified)
    val nextTopUp = verifiedCards.minOfOrNull {
        it.recommendedTopUpDate(snapshot.settings.reminderLeadDays)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("My Powercards", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                if (snapshot.cards.isEmpty()) {
                    "No cards have been provisioned yet."
                } else {
                    "${snapshot.cards.size} cards stored in encrypted local storage."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            nextTopUp?.let { dueDate ->
                Text(
                    if (today.isBefore(dueDate)) {
                        "Recommended top-up: ${dueDate.format(displayDateFormatter)}"
                    } else {
                        "Top-up due since: ${dueDate.format(displayDateFormatter)}"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        items(
            snapshot.cards.sortedWith(
                compareBy({ it.number.toLongOrNull() ?: Long.MAX_VALUE }, Powercard::number),
            ),
            key = Powercard::number,
        ) { card ->
            PowercardDetails(
                card = card,
                onEdit = { editing = card },
                onPresent = { presenting = card },
            )
        }
    }
    editing?.let { card ->
        EditPowercardDialog(
            card = card,
            onDismiss = { editing = null },
            onSave = {
                onUpdateCard(it)
                editing = null
            },
        )
    }
    presenting?.let { card ->
        PresentPowercardDialog(card = card, onDismiss = { presenting = null })
    }
}

@Composable
private fun PowercardDetails(
    card: Powercard,
    onEdit: () -> Unit,
    onPresent: () -> Unit,
) {
    val background = when {
        !card.detailsVerified -> MaterialTheme.colorScheme.surfaceVariant
        card.tier == CardTier.PLATINUM -> Color(0xFF30373B)
        else -> Color(0xFFE4B84E)
    }
    val foreground = when {
        !card.detailsVerified -> MaterialTheme.colorScheme.onSurfaceVariant
        card.tier == CardTier.PLATINUM -> Color.White
        else -> Color(0xFF241B00)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background, contentColor = foreground),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(card.number, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (card.detailsVerified) card.tier.name else "PHOTO ONLY",
                    fontWeight = FontWeight.Bold,
                )
            }
            Text("Barcode ${card.barcodeValue}")
            if (card.detailsVerified) {
                Text("${formatMoney(card.balanceCents)} game credit • ${String.format(Locale.US, "%,d", card.tickets)} e-Tickets")
                Text("Credit expires ${card.creditExpiry.format(displayDateFormatter)}")
                Text("Last qualifying $1+ venue reload ${card.lastQualifyingReload.format(displayDateFormatter)}")
                Text("Daily benefit window ends ${card.benefitExpiryDate().format(displayDateFormatter)}")
            } else {
                Text("Account values were not visible in the photo. Verify them before this card enters reminders or the queue.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("Edit", maxLines = 1) }
                OutlinedButton(onClick = onPresent) { Text("Present", maxLines = 1) }
            }
        }
    }
}

@Composable
private fun EditPowercardDialog(
    card: Powercard,
    onDismiss: () -> Unit,
    onSave: (Powercard) -> Unit,
) {
    var tier by remember(card.number) { mutableStateOf(card.tier) }
    var balance by remember(card.number) {
        mutableStateOf(if (card.detailsVerified) formatMoneyInput(card.balanceCents) else "")
    }
    var tickets by remember(card.number) {
        mutableStateOf(if (card.detailsVerified) card.tickets.toString() else "")
    }
    var creditExpiry by remember(card.number) {
        mutableStateOf(if (card.detailsVerified) card.creditExpiry.toString() else "")
    }
    var lastReload by remember(card.number) {
        mutableStateOf(if (card.detailsVerified) card.lastQualifyingReload.toString() else "")
    }
    val balanceCents = parseMoneyCents(balance)
    val ticketCount = tickets.toIntOrNull()?.takeIf { it >= 0 }
    val parsedExpiry = creditExpiry.parseIsoDateOrNull()
    val parsedReload = lastReload.parseIsoDateOrNull()
    val valid = balanceCents != null && ticketCount != null && parsedExpiry != null && parsedReload != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${card.number}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tier == CardTier.PLATINUM,
                        onClick = { tier = CardTier.PLATINUM },
                        label = { Text("Platinum") },
                    )
                    FilterChip(
                        selected = tier == CardTier.GOLD,
                        onClick = { tier = CardTier.GOLD },
                        label = { Text("Gold") },
                    )
                }
                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    label = { Text("Game credit (AUD)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = tickets,
                    onValueChange = { tickets = it },
                    label = { Text("e-Tickets") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = creditExpiry,
                    onValueChange = { creditExpiry = it },
                    label = { Text("Credit expiry (YYYY-MM-DD)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = lastReload,
                    onValueChange = { lastReload = it },
                    label = { Text("Last $1+ venue reload (YYYY-MM-DD)") },
                    singleLine = true,
                )
                if (!valid) Text("Enter valid non-negative values and ISO dates.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        card.copy(
                            tier = tier,
                            balanceCents = requireNotNull(balanceCents),
                            tickets = requireNotNull(ticketCount),
                            creditExpiry = requireNotNull(parsedExpiry),
                            lastQualifyingReload = requireNotNull(parsedReload),
                            detailsVerified = true,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
@Composable
private fun PresentPowercardDialog(
    card: Powercard,
    onDismiss: () -> Unit,
    queueInstruction: String? = null,
    onAcceptedAndNext: (() -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val barcode = remember(card.barcodeValue) { createCode128Barcode(card.barcodeValue) }
    val presentationStatus = rememberNfcPresentationStatus(context)
    var hceActive by remember(card.number) { mutableStateOf(false) }
    ActivateNfcPresentation(card.number) { hceActive = it }

    AlertDialog(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text("Present ${card.number}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                queueInstruction?.let { instruction ->
                    Text(
                        instruction,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Image(
                    bitmap = barcode.asImageBitmap(),
                    contentDescription = "Barcode for card ${card.number}",
                    modifier = Modifier.fillMaxWidth().height(112.dp),
                )
                Text(
                    card.barcodeValue,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                )
                Text(nfcStatus(nfcAdapter), fontWeight = FontWeight.SemiBold)
                Text(
                    if (hceActive) {
                        "Experimental Smart Tap is armed for Powercard ${card.number}. Keep this dialog open and the phone unlocked while tapping."
                    } else {
                        "Direct NFC presentation could not be activated. Use the barcode or check that NFC is on."
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                presentationStatus
                    ?.takeIf { it.cardNumber == card.number }
                    ?.let { status ->
                        Text(
                            status.message,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                Text(
                    "This mode implements the Smart Tap reader exchange directly and sends the selected Powercard number as its loyalty identifier. It is not issued or approved by Timezone or Google; keep the barcode and Wallet actions available as fallbacks.",
                    style = MaterialTheme.typography.bodySmall,
                )
                onAcceptedAndNext?.let { advance ->
                    Button(
                        onClick = advance,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Accepted — advance queue", maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = { openOfficialTimezoneApp(context, card) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Copy number & open Timezone", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { openGoogleWallet(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open current Wallet pass", maxLines = 1)
                }
                if (nfcAdapter != null && !nfcAdapter.isEnabled) {
                    TextButton(
                        onClick = { openNfcSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Turn on NFC", maxLines = 1)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SettingsScreen(
    snapshot: AppSnapshot,
    modifier: Modifier,
    permissionRefresh: Int,
    onSaveSettings: (AppSettings) -> Unit,
    onToggleManualManagement: (Boolean) -> Unit,
    onToggleReminders: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickSound: () -> Unit,
    onTestReminder: () -> Unit,
    onImportCards: () -> Unit,
    importMessage: String?,
) {
    val settings = snapshot.settings
    var venueName by remember(settings) { mutableStateOf(settings.venue.name) }
    var latitude by remember(settings) { mutableStateOf(settings.venue.latitude.toString()) }
    var longitude by remember(settings) { mutableStateOf(settings.venue.longitude.toString()) }
    var radius by remember(settings) { mutableStateOf(settings.venue.radiusMeters.toInt().toString()) }
    var leadDays by remember(settings) { mutableStateOf(settings.reminderLeadDays.toString()) }
    var threshold by remember(settings) { mutableStateOf(formatMoneyInput(settings.fundedBalanceThresholdCents)) }
    var audible by remember(settings) { mutableStateOf(settings.audibleReminder) }
    var vibrate by remember(settings) { mutableStateOf(settings.vibrateReminder) }
    val parsedLatitude = latitude.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val parsedLongitude = longitude.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val parsedRadius = radius.toFloatOrNull()?.takeIf { it in 100f..5_000f }
    val parsedLeadDays = leadDays.toIntOrNull()?.takeIf { it in 0..91 }
    val parsedThreshold = parseMoneyCents(threshold)
    val valid = venueName.isNotBlank() && parsedLatitude != null && parsedLongitude != null &&
        parsedRadius != null && parsedLeadDays != null && parsedThreshold != null
    val context = androidx.compose.ui.platform.LocalContext.current
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val backgroundLocationGranted = remember(permissionRefresh) {
        VenueGeofenceRegistrar.hasBackgroundLocation(context)
    }
    val dueCards = ReminderNotifier.dueCards(snapshot, LocalDate.now())

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Manual card management", fontWeight = FontWeight.SemiBold)
                Text(
                    "Show card values, editing, and imported barcode details.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = settings.manualCardManagementEnabled,
                onCheckedChange = onToggleManualManagement,
            )
        }
        HorizontalDivider()
        Text("Secure card provisioning", style = MaterialTheme.typography.titleLarge)
        Text(
            if (snapshot.cards.isEmpty()) {
                "Card values are not bundled in the app. Import your provisioning JSON after installation; the saved catalog is encrypted with the Android Keystore."
            } else {
                "Replace the encrypted card catalog with a new provisioning JSON file. Do not share the file after export."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = onImportCards,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (snapshot.cards.isEmpty()) "Import card data" else "Replace card data")
        }
        importMessage?.let { message ->
            Text(
                message,
                color = if (message.startsWith("Import failed")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        HorizontalDivider()
        Text("NFC & presentation", style = MaterialTheme.typography.titleLarge)
        Text(nfcStatus(nfcAdapter), fontWeight = FontWeight.SemiBold)
        Text(
            "Present opens an experimental direct Smart Tap session for the selected Powercard while its dialog stays open. It sends the Powercard number as the loyalty identifier through Android HCE without changing Google Wallet. This reverse-engineered path is not Timezone-approved; keep the barcode and Wallet actions available as fallbacks.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = { openGoogleWallet(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open Google Wallet", maxLines = 1)
        }
        if (nfcAdapter != null && !nfcAdapter.isEnabled) {
            TextButton(
                onClick = { openNfcSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Turn on NFC", maxLines = 1)
            }
        }
        HorizontalDivider()
        Text("Venue reminders", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Timezone's current Rewards terms require a $1+ sale every 92 days and an in-venue transaction for the daily yellow-reader benefit.",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Enable proximity reminders", fontWeight = FontWeight.SemiBold)
                Text(
                    if (backgroundLocationGranted) "Background location is available" else "Background location still needs approval",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = settings.remindersEnabled,
                onCheckedChange = onToggleReminders,
            )
        }
        if (!backgroundLocationGranted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequestPermissions) { Text("Request access") }
                OutlinedButton(onClick = onOpenSettings) { Text("App settings") }
            }
            Text(
                "On Android 11+, choose Permissions → Location → Allow all the time so entry geofences and due-date checks can run offline in the background.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider()
        Text("Selected venue", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = venueName,
            onValueChange = { venueName = it },
            label = { Text("Venue name") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = latitude,
            onValueChange = { latitude = it },
            label = { Text("Latitude") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = longitude,
            onValueChange = { longitude = it },
            label = { Text("Longitude") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = radius,
            onValueChange = { radius = it },
            label = { Text("Reminder radius (metres, 100–5000)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = leadDays,
            onValueChange = { leadDays = it },
            label = { Text("Recommend top-up this many days early") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = threshold,
            onValueChange = { threshold = it },
            label = { Text("Funded-card threshold (AUD)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        HorizontalDivider()
        Text("Reminder behavior", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = audible, onCheckedChange = { audible = it })
            Text("Play a notification sound")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = vibrate, onCheckedChange = { vibrate = it })
            Text("Vibrate")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickSound, enabled = audible) { Text("Choose sound") }
            OutlinedButton(onClick = onTestReminder) { Text("Test reminder") }
        }
        Text(
            if (settings.reminderSoundUri == null) "Sound: system default" else "Sound: custom selection",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!valid) Text("Fix invalid venue or reminder values before saving.", color = MaterialTheme.colorScheme.error)
        Button(
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                onSaveSettings(
                    settings.copy(
                        venue = VenueSettings(
                            name = venueName.trim(),
                            latitude = requireNotNull(parsedLatitude),
                            longitude = requireNotNull(parsedLongitude),
                            radiusMeters = requireNotNull(parsedRadius),
                        ),
                        reminderLeadDays = requireNotNull(parsedLeadDays),
                        fundedBalanceThresholdCents = requireNotNull(parsedThreshold),
                        audibleReminder = audible,
                        vibrateReminder = vibrate,
                    ),
                )
            },
        ) {
            Text("Save reminder settings", maxLines = 1)
        }
        HorizontalDivider()
        Text("Top-up status", style = MaterialTheme.typography.titleLarge)
        Text(
            if (dueCards.isEmpty()) {
                "No verified card has reached its recommended top-up date."
            } else {
                "${dueCards.size} verified card${if (dueCards.size == 1) "" else "s"} currently need a $1+ in-venue reload."
            },
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun rememberNfcPresentationStatus(context: Context): NfcPresentationStatus? {
    var status by remember(context) { mutableStateOf(NfcPresentationState.loadStatus(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                status = NfcPresentationState.loadStatus(context)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(NfcPresentationState.ACTION_STATUS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return status
}

@Composable
private fun ActivateNfcPresentation(
    cardNumber: String,
    onActiveChanged: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context.findActivity()
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    DisposableEffect(activity, nfcAdapter, cardNumber) {
        NfcPresentationState.setOverrideCard(context, cardNumber)
        val cardEmulation = nfcAdapter?.let { adapter ->
            runCatching { CardEmulation.getInstance(adapter) }.getOrNull()
        }
        val active = if (activity != null && nfcAdapter?.isEnabled == true) {
            runCatching {
                cardEmulation?.setPreferredService(
                    activity,
                    ComponentName(context, SmartTapHostApduService::class.java),
                ) == true
            }.getOrDefault(false)
        } else {
            false
        }
        onActiveChanged(active)
        onDispose {
            if (activity != null) {
                runCatching { cardEmulation?.unsetPreferredService(activity) }
            }
            NfcPresentationState.setOverrideCard(context, null)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun formatMoney(cents: Int): String = String.format(Locale.US, "$%.2f", cents / 100.0)

private fun formatMoneyInput(cents: Int): String = String.format(Locale.US, "%.2f", cents / 100.0)

private fun parseMoneyCents(value: String): Int? = runCatching {
    value.trim()
        .removePrefix("$")
        .toBigDecimal()
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .intValueExact()
        .takeIf { it >= 0 }
}.getOrNull()

private fun String.parseIsoDateOrNull(): LocalDate? = try {
    LocalDate.parse(trim())
} catch (_: DateTimeParseException) {
    null
}
private const val MAX_PROVISIONING_FILE_BYTES = 1_048_576

private fun readUtf8Limited(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_PROVISIONING_FILE_BYTES)
        output.write(buffer, 0, count)
    }
    return output.toByteArray().toString(StandardCharsets.UTF_8)
}

private fun openOfficialTimezoneApp(context: Context, card: Powercard) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Powercard number", card.number))
    val powercardsUri = TIMEZONE_POWERCARDS_URL.toUri()
    val appIntent = Intent(Intent.ACTION_VIEW, powercardsUri).setPackage(TIMEZONE_PACKAGE)
    if (runCatching { context.startActivity(appIntent) }.isSuccess) return
    context.startActivity(Intent(Intent.ACTION_VIEW, powercardsUri))
}

private fun openGoogleWallet(context: Context) {
    context.packageManager.getLaunchIntentForPackage(GOOGLE_WALLET_PACKAGE)?.let {
        context.startActivity(it)
        return
    }
    val marketIntent = Intent(
        Intent.ACTION_VIEW,
        "market://details?id=$GOOGLE_WALLET_PACKAGE".toUri(),
    )
    if (runCatching { context.startActivity(marketIntent) }.isFailure) {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                "https://play.google.com/store/apps/details?id=$GOOGLE_WALLET_PACKAGE".toUri(),
            ),
        )
    }
}

private fun openNfcSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
}

private fun nfcStatus(adapter: NfcAdapter?): String = when {
    adapter == null -> "This phone does not expose NFC hardware."
    adapter.isEnabled -> "NFC is on."
    else -> "NFC is off."
}

private fun createCode128Barcode(value: String): Bitmap {
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.CODE_128, 1_200, 320)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val rowOffset = y * matrix.width
        for (x in 0 until matrix.width) {
            pixels[rowOffset + x] = if (matrix[x, y]) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

private const val TIMEZONE_PACKAGE = "com.teegloyalty.timezone"
private const val TIMEZONE_POWERCARDS_URL = "https://tzg.fun/powercards"
private const val GOOGLE_WALLET_PACKAGE = "com.google.android.apps.walletnfcrel"

@Suppress("DEPRECATION")
private fun Intent.pickedRingtoneUri(): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
} else {
    getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
}
