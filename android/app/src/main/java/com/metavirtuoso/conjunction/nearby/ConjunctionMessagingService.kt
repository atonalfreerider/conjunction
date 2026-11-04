package com.metavirtuoso.conjunction.nearby

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.metavirtuoso.conjunction.ConjunctionApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FCM entry point. Handles kind = match | birthday_twin | exact_twin | conjunction | message
 * (and the SPEC 5 aliases connected / intro).
 *
 * In the foreground the event goes straight to the app, which plays the full-screen popup
 * (twins get the lavish variant) instead of a notification. In the background this posts a
 * rich notification with the matching popup art (BigPicture) that deep-links to the popup.
 * Note: messages that carry a `notification` block are drawn by the system while the app is in
 * the background (onMessageReceived is not called); tapping them still deep-links, because the
 * data keys arrive as launch-intent extras. Data-only pushes get the art in every state.
 */
class ConjunctionMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val event = PushEvent.fromData(message.data, message.notification?.title, message.notification?.body) ?: return
        val container = (application as ConjunctionApp).container
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (foreground && event.kind != PushKind.MESSAGE) {
            container.nearby.emit(event)
        } else {
            Notifications.show(this, event)
        }
    }

    /**
     * New or refreshed FCM registration (firebase-messaging 26 replaces onNewToken with this).
     * Called on FCM's background worker, so the upload blocks here (bounded) rather than racing
     * the service's destruction.
     */
    override fun onRegistered(token: String) {
        val api = (application as ConjunctionApp).container.nearbyApi
        if (!api.isConfigured || api.uid == null) return
        runBlocking {
            withTimeoutOrNull(20_000) { runCatching { api.registerPushToken(token) } }
        }
    }
}
