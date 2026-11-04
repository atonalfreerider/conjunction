package com.metavirtuoso.conjunction.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.nearby.Notifications
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Optional morning reminder ("Today's reading is ready"). The reading itself is written on device
 * when the app opens; this only posts a notification. Inexact repeating alarm at the chosen time (08:00 by default),
 * rescheduled after reboot by [DailyReminderReceiver]. No exact-alarm permission is needed.
 */
object DailyReminder {
    private const val REQUEST = 4201

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, DailyReminderReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val prefs = context.getSharedPreferences("reminder", Context.MODE_PRIVATE)
        val at = LocalTime.of(prefs.getInt("hour", 8).coerceIn(0, 23), prefs.getInt("minute", 0).coerceIn(0, 59))
        var next = LocalDate.now(zone).atTime(at).atZone(zone)
        if (!next.toInstant().isAfter(java.time.Instant.now())) next = next.plusDays(1)
        alarm.setInexactRepeating(AlarmManager.RTC, next.toInstant().toEpochMilli(), AlarmManager.INTERVAL_DAY, intent(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intent(context))
    }

    const val ACTION_FIRE = "com.metavirtuoso.conjunction.DAILY_READING"
}

class DailyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("reminder", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", false)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> if (enabled) DailyReminder.schedule(context)
            DailyReminder.ACTION_FIRE -> if (enabled && Notifications.canPost(context)) {
                val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_DAILY)
                    .setSmallIcon(R.drawable.ic_stat_conjunction)
                    .setColor(ContextCompat.getColor(context, R.color.gold))
                    .setContentTitle(context.getString(R.string.daily_reminder_title))
                    .setContentText(context.getString(R.string.daily_reminder_body))
                    .setAutoCancel(true)
                    .setContentIntent(Notifications.openAppIntent(context, null, 4202))
                    .build()
                try {
                    NotificationManagerCompat.from(context).notify(Notifications.ID_DAILY, notification)
                } catch (_: SecurityException) {
                }
            }
        }
    }

    companion object {
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("reminder", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
            if (enabled) DailyReminder.schedule(context) else DailyReminder.cancel(context)
        }

        fun isEnabled(context: Context) = context.getSharedPreferences("reminder", Context.MODE_PRIVATE).getBoolean("enabled", false)

        /** Settings › Notifications › Daily reading time (default 08:00); reschedules when on. */
        fun setTime(context: Context, time: LocalTime) {
            context.getSharedPreferences("reminder", Context.MODE_PRIVATE).edit().putInt("hour", time.hour).putInt("minute", time.minute).apply()
            if (isEnabled(context)) DailyReminder.schedule(context)
        }
    }
}
