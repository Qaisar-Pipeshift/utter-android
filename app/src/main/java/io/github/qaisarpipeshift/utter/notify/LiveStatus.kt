package io.github.qaisarpipeshift.utter.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.core.PanelState
import io.github.qaisarpipeshift.utter.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The notch status while a take is in flight (docs/spec.md §3b, docs/plan.md Task 10).
 *
 * Why a phase instead of a state copy: the controller emits a new [PanelState.Recording] ten times a second for the
 * waveform, while the notification only changes when the phase changes — listening, transcribing, failed. Re-posting
 * on every emission would hammer the notification service for no visible difference; a small phase machine keeps it
 * to one post per transition. The notification exists only for Recording → Finishing and ten seconds after a
 * failure, so it costs nothing while the keyboard is idle.
 *
 * Android 16 (API 36) can promote an ongoing notification into the OnePlus Live Alerts capsule / Samsung Now Bar;
 * we ask through `NotificationCompat.Builder.setRequestPromotedOngoing`, which on older platforms is a harmless
 * extra. Anything older, or a user who turned the feature off, gets the same content as a plain ongoing
 * notification.
 */
class LiveStatus(
    context: Context,
    private val settings: () -> Settings,
    private val scope: CoroutineScope,
) {
    private enum class Phase { NONE, RECORDING, FINISHING, FAILED }

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)
    private var phase = Phase.NONE
    private var startedAt = 0L
    private var dismissJob: Job? = null

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.utter_notify_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        appContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Feed every controller state; only phase transitions touch the notification service. */
    fun onState(state: PanelState) {
        val next = when (state) {
            is PanelState.Recording -> Phase.RECORDING
            is PanelState.Finishing -> Phase.FINISHING
            is PanelState.Failed -> Phase.FAILED
            else -> Phase.NONE
        }
        if (next == phase) return
        phase = next
        dismissJob?.cancel()
        dismissJob = null
        when (next) {
            Phase.NONE -> {
                startedAt = 0L
                manager.cancel(NOTIFICATION_ID)
            }

            Phase.RECORDING -> {
                if (startedAt == 0L) startedAt = System.currentTimeMillis()
                post(appContext.getString(R.string.utter_listening), chronometer = true)
            }

            Phase.FINISHING -> post(appContext.getString(R.string.utter_transcribing), chronometer = false)

            Phase.FAILED -> {
                startedAt = 0L
                post(appContext.getString(R.string.utter_notify_failed), chronometer = false)
                dismissJob = scope.launch {
                    delay(FAILED_VISIBLE_MS)
                    if (phase == Phase.FAILED) {
                        phase = Phase.NONE
                        manager.cancel(NOTIFICATION_ID)
                    }
                }
            }
        }
    }

    /** Removes the notification and any pending auto-dismiss; called when the IME goes away. */
    fun cancel() {
        dismissJob?.cancel()
        dismissJob = null
        phase = Phase.NONE
        startedAt = 0L
        manager.cancel(NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission") // allowed() checks POST_NOTIFICATIONS and the app's notification setting first.
    private fun post(title: String, chronometer: Boolean) {
        if (!settings().notch || !allowed()) {
            // Silent skip (spec §3b): keep the phase so a tick cannot repeat this binder call every 100 ms.
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (chronometer) {
            builder.setWhen(startedAt).setShowWhen(true).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false).setUsesChronometer(false)
        }
        if (Build.VERSION.SDK_INT >= PROMOTED_API) {
            builder.setRequestPromotedOngoing(true)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun allowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    private companion object {
        const val CHANNEL_ID = "recording_status"
        const val NOTIFICATION_ID = 1
        const val FAILED_VISIBLE_MS = 10_000L

        /** Android 16 / API 36 introduced promoted ongoing notifications (docs/spec.md §3b). */
        const val PROMOTED_API = 36
    }
}
