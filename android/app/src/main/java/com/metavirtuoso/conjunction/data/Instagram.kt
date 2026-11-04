package com.metavirtuoso.conjunction.data

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.metavirtuoso.conjunction.core.profile.InstagramHandle

/**
 * Instagram on the phone (SPEC 5d.6): there is no Instagram sign-in or import. The user types or
 * pastes a handle / profile link, or shares a profile link from the Instagram app to Conjunction.
 * A friend's chip opens their profile with a plain https link (Instagram's verified app links open
 * the app when it is installed, else the browser), so no `<queries>` entry is needed.
 */
object Instagram {
    /** The intent the chip starts: ACTION_VIEW of `https://www.instagram.com/<handle>/`. */
    fun profileIntent(handle: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(InstagramHandle.profileUrl(handle))).addCategory(Intent.CATEGORY_BROWSABLE)

    /** Opens the profile; false when nothing on the phone can open a web link. */
    fun open(context: Context, handle: String): Boolean = try {
        val intent = profileIntent(handle)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    /**
     * ACTION_SEND text/plain shared to the app: the handle when the text holds an
     * `instagram.com/<handle>` profile link, else null ("Conjunction can only use an Instagram
     * profile link here.").
     */
    fun handleFromShare(intent: Intent?): ShareResult? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("text/") != true) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        return handleFromSharedText(text)
    }

    fun handleFromSharedText(text: String): ShareResult {
        if (!text.contains("instagram.com/", ignoreCase = true)) return ShareResult.NotAProfile
        return InstagramHandle.normalize(text)?.let { ShareResult.Handle(it) } ?: ShareResult.NotAProfile
    }

    sealed interface ShareResult {
        data class Handle(val handle: String) : ShareResult
        data object NotAProfile : ShareResult
    }

    /** The Paste button: the clipboard's text (read only when the person taps Paste). */
    fun clipboardText(context: Context): String? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }
}
