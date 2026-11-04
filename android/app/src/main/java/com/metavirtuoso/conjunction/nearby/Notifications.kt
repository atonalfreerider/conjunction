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

/**
 * Local notifications (there is no push service: SPEC 5c). Channels: matches & twins, messages,
 * the ongoing Nearby status, and the daily reading reminder. Every notification deep-links into
 * the app: matches open the match animation, messages open the chat.
 */
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
                description = "New chat messages from your matches (the text is never shown in the notification)."
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
            NotificationChannel(CHANNEL_NEARBY_SERVICE, "Nearby status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Nearby is on and using Bluetooth."
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

    fun openAppIntent(context: Context, event: NearbyEvent?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (event != null) {
                putExtra(NearbyEvent.EXTRA_KIND, event.kind.wire)
                event.encounterId?.let { putExtra(NearbyEvent.EXTRA_ENCOUNTER, it) }
                event.friendId?.let { putExtra(NearbyEvent.EXTRA_FRIEND, it) }
                event.matchKind?.let { putExtra(NearbyEvent.EXTRA_MATCH_KIND, it) }
                event.total?.let { putExtra(NearbyEvent.EXTRA_TOTAL, it.toString()) }
            }
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Art shown in the expanded notification for each kind (the popup illustrations). */
    fun artFor(kind: NearbyEventKind): Int? = when (kind) {
        NearbyEventKind.MATCH -> R.drawable.popup_match_nearby
        NearbyEventKind.BIRTHDAY_TWIN, NearbyEventKind.EXACT_TWIN -> R.drawable.popup_birthday_twins
        NearbyEventKind.CONJUNCTION -> R.drawable.popup_its_a_conjunction
        NearbyEventKind.MESSAGE -> null
    }

    /** Title and text (legal/IN_APP_CONSENT_COPY.md section 10). [sunSign] = display name ("Leo"). */
    fun text(context: Context, event: NearbyEvent, peerName: String?, sunSign: String?): Pair<String, String> {
        val app = context.getString(R.string.app_name)
        val name = peerName?.takeIf { it.isNotBlank() } ?: "your match"
        return when (event.kind) {
            NearbyEventKind.MATCH -> app to
                if (event.matchKind == "friends") context.getString(R.string.notif_match_friends, sunSign ?: "kindred", event.total ?: 0)
                else context.getString(R.string.notif_match_dating, sunSign ?: "kindred spirit", event.total ?: 0)
            NearbyEventKind.BIRTHDAY_TWIN -> app to context.getString(R.string.notif_birthday_twin)
            NearbyEventKind.EXACT_TWIN -> app to context.getString(R.string.notif_exact_twin)
            NearbyEventKind.CONJUNCTION -> app to context.getString(R.string.notif_matched, name)
            NearbyEventKind.MESSAGE -> app to context.getString(R.string.notif_message, name)
        }
    }

    /** Notification tag: one per match / friend, so repeats replace rather than pile up. */
    fun tagFor(event: NearbyEvent): String = when (event.kind) {
        NearbyEventKind.MESSAGE -> "msg:${event.friendId}"
        NearbyEventKind.CONJUNCTION -> "friend:${event.friendId}"
        else -> "match:${event.encounterId}"
    }

    fun show(context: Context, event: NearbyEvent, peerName: String?, sunSign: String?) {
        if (!canPost(context)) return
        val (title, body) = text(context, event, peerName, sunSign)
        val channel = if (event.kind == NearbyEventKind.MESSAGE) CHANNEL_MESSAGES else CHANNEL_CONJUNCTIONS
        val tag = tagFor(event)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_conjunction)
            .setColor(ContextCompat.getColor(context, R.color.gold))
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setCategory(if (event.kind == NearbyEventKind.MESSAGE) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openAppIntent(context, event, tag.hashCode()))
        artFor(event.kind)?.let { art ->
            val bitmap = BitmapFactory.decodeResource(context.resources, art)
            builder.setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.brand_mark))
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).setSummaryText(body))
        }
        try {
            NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    fun cancel(context: Context, event: NearbyEvent) {
        NotificationManagerCompat.from(context).cancel(tagFor(event), NOTIFICATION_ID)
    }

    private const val NOTIFICATION_ID = 4300
}
