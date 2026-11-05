package com.droiddock.companion.messages

import android.media.session.PlaybackState

/** Used only after matching the notification's token and package to a live media session. */
object MediaNotificationPolicy {
    fun isControllable(state: Int?, actions: Long): Boolean {
        val transportActions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        return state != null && state != PlaybackState.STATE_NONE && state != PlaybackState.STATE_STOPPED &&
            state != PlaybackState.STATE_ERROR && actions and transportActions != 0L
    }
}
