package com.metavirtuoso.conjunction.nearby

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.metavirtuoso.conjunction.BuildConfig
import com.metavirtuoso.conjunction.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Report = block + an email the person sends themselves (SPEC 5c: the only channel to the
 * developer). legal/IN_APP_CONSENT_COPY.md section 8: to the support address, subject
 * "Conjunction report: <reason>", with the reported card, the reason, a description, app version and
 * platform, and the chat text only when the person ticks "Include our messages".
 */
object ReportEmail {
    data class Subject(
        val firstName: String?,
        val age: Int?,
        val sun: String?,
        val moon: String?,
        val rising: String?,
        val matchKind: String?,
        /** Profile fingerprint (strangers) or friend id: the "temporary ID" in the email. */
        val localId: String?,
        /** Friends only: their safety code (identity-key fingerprint, the same digits on both phones). */
        val identityFingerprint: String?,
        val hasPhoto: Boolean,
    )

    fun body(context: Context, subject: Subject, reason: ReportReason, description: String, messages: List<ChatLine>?): String = buildString {
        val time = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        appendLine("Reason: ${context.getString(reason.label)}")
        appendLine()
        appendLine("What happened:")
        appendLine(description.trim().ifEmpty { "(not given)" })
        appendLine()
        appendLine("Reported card")
        appendLine("  First name: ${subject.firstName ?: "(not shown)"}")
        subject.age?.takeIf { it > 0 }?.let { appendLine("  Age: $it") }
        appendLine("  Signs: sun ${subject.sun ?: "?"}, moon ${subject.moon ?: "?"}, rising ${subject.rising ?: "unknown"}")
        subject.matchKind?.let { appendLine("  Match kind: $it") }
        appendLine("  Photo on card: ${if (subject.hasPhoto) "yes (attach a screenshot if relevant)" else "no"}")
        subject.localId?.let { appendLine("  Temporary ID: $it") }
        subject.identityFingerprint?.let { appendLine("  Identity-key fingerprint (safety code): $it") }
        if (messages != null) {
            appendLine()
            appendLine("Our messages (${messages.size}):")
            messages.forEach { m ->
                val at = Instant.ofEpochMilli(m.ts).atZone(ZoneId.systemDefault()).toLocalDateTime().format(time)
                appendLine("  [$at] ${if (m.mine) "Me" else subject.firstName ?: "Them"}: ${m.text}")
            }
        }
        appendLine()
        appendLine("App: ${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${android.os.Build.VERSION.RELEASE}")
    }

    fun subjectLine(context: Context, reason: ReportReason): String =
        "${context.getString(R.string.app_name)} report: ${reason.code}"

    /**
     * Opens the email app with everything prefilled (ACTION_SENDTO mailto:, so only email apps
     * answer). Returns false when there is no email app; the report is then copied to the clipboard.
     */
    fun open(context: Context, subject: String, body: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(subject, "To: ${BuildConfig.SUPPORT_EMAIL}\nSubject: $subject\n\n$body"))
            false
        }
    }
}
