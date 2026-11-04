package com.metavirtuoso.conjunction.nearby

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.metavirtuoso.conjunction.MainActivity
import com.metavirtuoso.conjunction.R

/** The push / in-app event kinds (backend notify.ts, plus the SPEC 5 names as aliases). */
enum class PushKind(val wire: String) {
    MATCH("match"),
    BIRTHDAY_TWIN("birthday_twin"),
    EXACT_TWIN("exact_twin"),
    /** Both swiped right ("It's a Conjunction!"); SPEC 5 called it "connected". */
    CONJUNCTION("conjunction"),
    /** A chat message; SPEC 5 called the first one "intro". */
    MESSAGE("message");

    val isTwin: Boolean get() = this == BIRTHDAY_TWIN || this == EXACT_TWIN

    companion object {
        fun fromWire(value: String?): PushKind? = when (value) {
            "connected" -> CONJUNCTION
            "intro" -> MESSAGE
            else -> entries.firstOrNull { it.wire == value }
        }
    }
}

/** A notification-worthy event, from FCM or the in-app listener. */
data class PushEvent(
    val kind: PushKind,
    val encounterId: String?,
    val pairId: String?,
    val matchKind: String?,
    val total: Int?,
    val title: String?,
    val body: String?,
) {
    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ENCOUNTER = "encounterId"
        const val EXTRA_PAIR = "pairId"
        const val EXTRA_MATCH_KIND = "matchKind"
        const val EXTRA_TOTAL = "total"

        /** Reads the event from a data map (FCM data payload or launch-intent extras). */
        fun fromData(data: Map<String, String?>, title: String? = null, body: String? = null): PushEvent? {
            val kind = PushKind.fromWire(data[EXTRA_KIND]) ?: return null
            return PushEvent(kind, data[EXTRA_ENCOUNTER], data[EXTRA_PAIR], data[EXTRA_MATCH_KIND], data[EXTRA_TOTAL]?.toIntOrNull(), title, body)
        }

        fun fromIntent(intent: Intent?): PushEvent? {
            val extras = intent?.extras ?: return null
            val map = listOf(EXTRA_KIND, EXTRA_ENCOUNTER, EXTRA_PAIR, EXTRA_MATCH_KIND, EXTRA_TOTAL).associateWith { extras.getString(it) }
            return fromData(map)
        }
    }
}

/** Notification channels and builders. Channel ids "conjunctions" / "messages" match the backend. */
object Notifications {
    const val CHANNEL_CONJUNCTIONS = "conjunctions"
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_NEARBY_SERVICE = "nearby_service"
    const val CHANNEL_DAILY = "daily_reading"

    const val ID_NEARBY_SERVICE = 4100
    const val ID_DAILY = 4200

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_CONJUNCTIONS, "Conjunctions & birthday twins", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When a compatible person is nearby, a birthday twin appears, or you both say yes."
            },
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New chat messages from your matches (the text is never shown on the lock screen)."
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
            NotificationChannel(CHANNEL_NEARBY_SERVICE, "Nearby status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Nearby is on and sharing your location."
                setShowBadge(false)
            },
            NotificationChannel(CHANNEL_DAILY, "Daily reading", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A morning reminder that today's horoscope is ready."
            },
        ))
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun openAppIntent(context: Context, event: PushEvent?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (event != null) {
                putExtra(PushEvent.EXTRA_KIND, event.kind.wire)
                event.encounterId?.let { putExtra(PushEvent.EXTRA_ENCOUNTER, it) }
                event.pairId?.let { putExtra(PushEvent.EXTRA_PAIR, it) }
                event.matchKind?.let { putExtra(PushEvent.EXTRA_MATCH_KIND, it) }
                event.total?.let { putExtra(PushEvent.EXTRA_TOTAL, it.toString()) }
            }
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Art shown in the expanded notification for each kind (the popup illustrations). */
    fun artFor(kind: PushKind): Int? = when (kind) {
        PushKind.MATCH -> R.drawable.popup_match_nearby
        PushKind.BIRTHDAY_TWIN, PushKind.EXACT_TWIN -> R.drawable.popup_birthday_twins
        PushKind.CONJUNCTION -> R.drawable.popup_its_a_conjunction
        PushKind.MESSAGE -> null
    }

    /** Default copy (legal/IN_APP_CONSENT_COPY.md section 9) when the push carried none. */
    fun defaultText(context: Context, event: PushEvent): Pair<String, String> = when (event.kind) {
        PushKind.MATCH -> context.getString(R.string.app_name) to
            if (event.matchKind == "friends") context.getString(R.string.notif_match_friends, "kindred", event.total ?: 0)
            else context.getString(R.string.notif_match_dating, "kindred spirit", event.total ?: 0)
        PushKind.BIRTHDAY_TWIN -> "Birthday twin!" to context.getString(R.string.notif_birthday_twin)
        PushKind.EXACT_TWIN -> "Exact twin!" to context.getString(R.string.notif_exact_twin)
        PushKind.CONJUNCTION -> "It's a Conjunction!" to "You both said yes. Say hello."
        PushKind.MESSAGE -> context.getString(R.string.app_name) to "You have a new message."
    }

    fun show(context: Context, event: PushEvent) {
        if (!canPost(context)) return
        val (defaultTitle, defaultBody) = defaultText(context, event)
        val title = event.title ?: defaultTitle
        val body = event.body ?: defaultBody
        val channel = if (event.kind == PushKind.MESSAGE) CHANNEL_MESSAGES else CHANNEL_CONJUNCTIONS
        val tag = event.encounterId ?: event.pairId ?: event.kind.wire
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_conjunction)
            .setColor(ContextCompat.getColor(context, R.color.gold))
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setCategory(if (event.kind == PushKind.MESSAGE) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openAppIntent(context, event, tag.hashCode()))
        artFor(event.kind)?.let { art ->
            val bitmap = BitmapFactory.decodeResource(context.resources, art)
            builder.setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.brand_mark))
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).setSummaryText(body))
        }
        try {
            NotificationManagerCompat.from(context).notify(tag, tag.hashCode(), builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }
}
