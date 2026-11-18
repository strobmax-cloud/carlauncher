package com.goga.carlauncher

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent

/**
 * v1.7.7: «−10» / «+10» on the floating pill — seek the player that is in front (SmartTube, YouTube, any video/music app).
 *
 * Order: 1) MediaController.transportControls.seekTo(position ± step) when the session allows ACTION_SEEK_TO (SmartTube:
 * ExoPlayer MediaSessionConnector → player.seekTo, exact step); 2) fastForward() / rewind() (the player's own step — in
 * SmartTube 15 s forward / 5 s back); 3) media key KEYCODE_MEDIA_FAST_FORWARD / REWIND through AudioManager (goes to the
 * app that last played; needs no permission). Key injection into another app (DPAD ←/→) is impossible without root —
 * accessibility services may not inject keys, and we never filter keys.
 * Sessions are read through the existing notification listener (MediaListener, «Доступ к музыке»).
 */
object Seek {
    val SMARTTUBE = listOf("com.liskovsoft.smarttubetv", "org.smarttube.", "app.smarttube.")
    fun isSmartTube(pkg: String?) = pkg != null && SMARTTUBE.any { pkg.startsWith(it) }
    /** known video players: buttons are offered even without session access (media keys only) */
    fun isVideoApp(pkg: String?) = isSmartTube(pkg) || pkg in listOf("com.google.android.youtube", "com.google.android.youtube.tv", "org.videolan.vlc",
        "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro", "is.xyz.mpv", "org.schabi.newpipe", "app.revanced.android.youtube", "com.vanced.android.youtube")

    enum class How { SEEK_TO, FF_REW, MEDIA_KEY, NONE }

    private fun sessions(c: Context): List<MediaController> = try {
        if (!MediaHub.listenerEnabled(c)) emptyList()
        else c.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(c, MediaListener::class.java)).filter { it.packageName != c.packageName }
    } catch (_: Throwable) { emptyList() }

    private fun live(s: Int?) = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING || s == PlaybackState.STATE_FAST_FORWARDING || s == PlaybackState.STATE_REWINDING
    private fun seekable(st: PlaybackState?) = st != null && (st.actions and (PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND)) != 0L

    /**
     * The session the buttons act on: the app in front ([fg]) playing or paused, else (front unknown) any playing one.
     * Pure choice over (package, state, actions) so it is unit-testable.
     */
    fun choose(list: List<Triple<String, Int, Long>>, fg: String?): Int {
        fun ok(i: Int) = (list[i].third and (PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND)) != 0L
        if (fg != null) {
            list.indices.firstOrNull { list[it].first == fg && live(list[it].second) && ok(it) }?.let { return it }
            list.indices.firstOrNull { list[it].first == fg && list[it].second == PlaybackState.STATE_PAUSED && ok(it) }?.let { return it }
            return -1
        }
        return list.indices.firstOrNull { live(list[it].second) && ok(it) } ?: -1
    }
    fun target(c: Context, fg: String?): MediaController? {
        val l = sessions(c)
        val i = choose(l.map { Triple(it.packageName, it.playbackState?.state ?: 0, it.playbackState?.actions ?: 0L) }, fg)
        return if (i >= 0) l[i] else null
    }
    /** show the seek buttons? — a seekable session of the app in front, or (no session access) a known video app in front */
    fun available(c: Context, fg: String?): Boolean {
        if (target(c, fg) != null) return true
        // a video app in front without a usable session (no «Доступ к музыке», or its session is off) → media keys
        return fg != null && isVideoApp(fg) && sessions(c).none { it.packageName == fg }
    }

    /** current position from the last reported one (players report it only on changes) */
    fun position(st: PlaybackState, now: Long = SystemClock.elapsedRealtime()): Long {
        var p = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) p += ((now - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        return maxOf(0L, p)
    }
    fun newPosition(st: PlaybackState, dur: Long, deltaMs: Long, now: Long = SystemClock.elapsedRealtime()): Long {
        val p = position(st, now) + deltaMs
        return (if (dur > 0) minOf(p, maxOf(0L, dur - 1000)) else p).coerceAtLeast(0L)
    }

    fun seek(c: Context, fg: String?, deltaMs: Long): How {
        val mc = target(c, fg)
        val st = mc?.playbackState
        if (mc != null && st != null && (st.actions and PlaybackState.ACTION_SEEK_TO) != 0L) {
            val dur = mc.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            try { mc.transportControls.seekTo(newPosition(st, dur, deltaMs)); return How.SEEK_TO } catch (_: Throwable) {}
        }
        if (mc != null && st != null) {
            val a = if (deltaMs > 0) PlaybackState.ACTION_FAST_FORWARD else PlaybackState.ACTION_REWIND
            if ((st.actions and a) != 0L) try { if (deltaMs > 0) mc.transportControls.fastForward() else mc.transportControls.rewind(); return How.FF_REW } catch (_: Throwable) {}
        }
        return if (mediaKey(c, if (deltaMs > 0) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_REWIND)) How.MEDIA_KEY else How.NONE
    }
    private fun mediaKey(c: Context, code: Int): Boolean = try {
        val am = c.getSystemService(AudioManager::class.java)
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)); am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0)); true
    } catch (_: Throwable) { false }
}
