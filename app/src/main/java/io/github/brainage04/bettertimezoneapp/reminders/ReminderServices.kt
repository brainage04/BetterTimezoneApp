package io.github.brainage04.bettertimezoneapp.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import io.github.brainage04.bettertimezoneapp.MainActivity
import io.github.brainage04.bettertimezoneapp.data.AppSnapshot
import io.github.brainage04.bettertimezoneapp.data.CardStore
import io.github.brainage04.bettertimezoneapp.data.Powercard
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object VenueGeofenceRegistrar {
    private const val GEOFENCE_REQUEST_ID = "selected-venue"
    private const val GEOFENCE_PENDING_INTENT_REQUEST = 7102

    fun sync(context: Context, snapshot: AppSnapshot): Boolean {
        ReminderScheduler.sync(context, snapshot.settings.remindersEnabled)
        if (!snapshot.settings.remindersEnabled) {
            LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
            return true
        }
        if (!hasBackgroundLocation(context)) return false

        val venue = snapshot.settings.venue
        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_REQUEST_ID)
            .setCircularRegion(venue.latitude, venue.longitude, venue.radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()
        return try {
            LocationServices.getGeofencingClient(context)
                .addGeofences(request, pendingIntent(context))
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun hasBackgroundLocation(context: Context): Boolean {
        val fineGranted = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            GEOFENCE_PENDING_INTENT_REQUEST,
            Intent(context, VenueGeofenceReceiver::class.java),
            flags,
        )
    }
}

class VenueGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            ReminderNotifier.notifyIfDue(context)
        }
    }
}

object ReminderScheduler {
    private const val WORK_NAME = "venue-proximity-reminder"

    fun sync(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<VenueReminderWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

class VenueReminderWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val today = LocalDate.now()
        val snapshot = CardStore(applicationContext).load(today)
        if (!snapshot.settings.remindersEnabled || ReminderNotifier.dueCards(snapshot, today).isEmpty()) {
            return Result.success()
        }
        if (!VenueGeofenceRegistrar.hasBackgroundLocation(applicationContext)) return Result.success()

        return try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setDurationMillis(20_000)
                .build()
            val location = Tasks.await(
                LocationServices.getFusedLocationProviderClient(applicationContext)
                    .getCurrentLocation(request, null),
                25,
                TimeUnit.SECONDS,
            ) ?: return Result.success()
            val venue = snapshot.settings.venue
            val distance = FloatArray(1)
            Location.distanceBetween(
                location.latitude,
                location.longitude,
                venue.latitude,
                venue.longitude,
                distance,
            )
            if (distance[0] <= venue.radiusMeters) {
                ReminderNotifier.notifyIfDue(applicationContext)
            }
            Result.success()
        } catch (_: SecurityException) {
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object ReminderNotifier {
    private const val CHANNEL_PREFIX = "venue-top-up-"
    private const val NOTIFICATION_ID = 7101

    fun dueCards(snapshot: AppSnapshot, today: LocalDate): List<Powercard> = snapshot.cards.filter { card ->
        card.detailsVerified &&
            !today.isBefore(card.recommendedTopUpDate(snapshot.settings.reminderLeadDays))
    }

    fun notifyIfDue(context: Context, forceTest: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val today = LocalDate.now()
        val store = CardStore(context)
        val snapshot = store.load(today)
        val due = dueCards(snapshot, today)
        if (!forceTest && due.isEmpty()) return false
        if (!forceTest && store.lastNotificationDate() == today) return false

        val settings = snapshot.settings
        val selectedSound = when {
            !settings.audibleReminder -> null
            settings.reminderSoundUri != null -> settings.reminderSoundUri.toUri()
            else -> Settings.System.DEFAULT_NOTIFICATION_URI
        }
        val channelId = CHANNEL_PREFIX + listOf(
            settings.audibleReminder,
            settings.vibrateReminder,
            settings.reminderSoundUri,
        ).hashCode()
        createNotificationChannel(context, channelId, selectedSound, settings.vibrateReminder)

        val activityIntent = Intent(context, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (forceTest && due.isEmpty()) {
            "BetterTimezone test reminder"
        } else {
            "Top up at ${settings.venue.name}"
        }
        val content = if (forceTest && due.isEmpty()) {
            "Your selected reminder sound and vibration are working."
        } else {
            val cardLabel = if (due.size == 1) "card" else "cards"
            "${due.size} $cardLabel need a $1+ in-venue reload."
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(io.github.brainage04.bettertimezoneapp.R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!settings.audibleReminder)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        if (!forceTest) store.setLastNotificationDate(today)
        return true
    }

    private fun createNotificationChannel(
        context: Context,
        channelId: String,
        sound: Uri?,
        vibrate: Boolean,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notificationChannels
            .filter { it.id.startsWith(CHANNEL_PREFIX) && it.id != channelId }
            .forEach { manager.deleteNotificationChannel(it.id) }
        val channel = NotificationChannel(
            channelId,
            "Venue top-up reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Reminders when a Powercard top-up is due and the phone is near the selected venue."
            enableVibration(vibrate)
            if (sound == null) {
                setSound(null, null)
            } else {
                setSound(
                    sound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .build(),
                )
            }
        }
        manager.createNotificationChannel(channel)
    }
}
