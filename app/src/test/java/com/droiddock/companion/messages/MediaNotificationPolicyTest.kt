package com.droiddock.companion.messages

import android.media.session.PlaybackState
import org.junit.Assert.*
import org.junit.Test

class MediaNotificationPolicyTest {
    @Test fun bedtimeWithoutPlaybackStateCannotBecomeAPlayer() {
        assertFalse(MediaNotificationPolicy.isControllable(null, PlaybackState.ACTION_PLAY_PAUSE))
        assertFalse(MediaNotificationPolicy.isControllable(PlaybackState.STATE_NONE, PlaybackState.ACTION_PLAY_PAUSE))
        assertFalse(MediaNotificationPolicy.isControllable(PlaybackState.STATE_PLAYING, 0))
    }
    @Test fun playingAndPausedSessionsRemainControllable() {
        assertTrue(MediaNotificationPolicy.isControllable(PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PAUSE))
        assertTrue(MediaNotificationPolicy.isControllable(PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY))
        assertFalse(MediaNotificationPolicy.isControllable(PlaybackState.STATE_STOPPED, PlaybackState.ACTION_PLAY))
    }
}
