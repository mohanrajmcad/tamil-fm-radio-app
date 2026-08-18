package com.example.tamilsfmradio

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * The manifest previously pointed the Cast SDK at its own OptionsProvider *interface*
 * instead of a real implementing class - harmless only because nothing had ever invoked
 * CastContext.getSharedInstance(). Now that the cast button actually initializes it, a
 * real implementation is required.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        // Without this, the only playback notification is the local Media3 one - which
        // controls the (paused) local player, not the Cast receiver actually making sound.
        // This gives casting its own system notification with real play/pause/skip wired to
        // the Cast session, and is also what lets hardware media buttons and Assistant
        // ("Hey Google, next") reach the receiver's queue.
        val notificationOptions = NotificationOptions.Builder()
            .setActions(
                listOf(
                    MediaIntentReceiver.ACTION_SKIP_PREV,
                    MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
                    MediaIntentReceiver.ACTION_SKIP_NEXT
                ),
                intArrayOf(0, 1, 2)
            )
            .setTargetActivityClassName(MainActivity::class.java.name)
            .setSmallIconDrawableResId(R.drawable.ic_notification)
            .build()

        val castMediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            .setExpandedControllerActivityClassName(MainActivity::class.java.name)
            .setMediaSessionEnabled(true)
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(castMediaOptions)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
