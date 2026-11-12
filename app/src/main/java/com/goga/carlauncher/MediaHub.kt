package com.goga.carlauncher

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent

/**
 * v1.7.0 — one place that follows ALL media sessions of the unit (Яндекс Музыка, Apple Music, Spotify, YouTube,
 * Teyes Radio / FM, local music, the Teyes Bluetooth-music app for the phone…) through the notification-listener
 * access, and always shows / controls the one that is PLAYING right now:
 *  • a callback is registered on every session (not only on the shown one), so a player that starts later
 *    (e.g. Яндекс Музыка after Spotify) takes over the card immediately;
 *  • nothing plays → the session that played most recently (also across restarts: Prefs.lastPlayer);
 *  • no session at all → prev / play / next are sent as media keys (AudioManager.dispatchMediaKeyEvent), which Android
 *    routes to the last media-button receiver — that is how phone Bluetooth audio (AVRCP) is reached when the BT app
 *    publishes no session.
 * Steering-wheel keys are NOT intercepted: the launcher only listens to sessions and sends its own button presses.
 */
object MediaHub {
    /** Pure description of a session for [choose] (unit-tested). */
    class Info(val pkg: String, val state: Int, val hasTitle: Boolean, val lastPlay: Long)

    fun isPlaying(state: Int?) = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
        state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING

    /**
     * Index of the session to show: playing ones first (the one that started most recently; a pinned app wins among
     * the playing), else the pinned app, else the most recently played, else one with a title, else Android's order.
     */
    fun choose(list: List<Info>, pinned: String? = null): Int {
        if (list.isEmpty()) return -1
        val idx = list.indices
        val playing = idx.filter { isPlaying(list[it].state) }
        if (playing.isNotEmpty()) {
            playing.firstOrNull { list[it].pkg == pinned }?.let { return it }
            return playing.maxWithOrNull(compareBy<Int> { list[it].lastPlay }.thenByDescending { it })!!
        }
        if (pinned != null) idx.firstOrNull { list[it].pkg == pinned }?.let { return it }
        idx.filter { list[it].lastPlay > 0 }.maxWithOrNull(compareBy<Int> { list[it].lastPlay }.thenByDescending { it })?.let { return it }
        idx.firstOrNull { list[it].hasTitle }?.let { return it }
        return 0
    }

    /** What the card shows / the buttons control. */
    enum class Kind { SESSION, PHONE, RADIO, NONE }
    /** Pure candidate for [pick] (unit-tested): a session, the phone over BT, or the head unit's radio without a session. */
    class Cand(val kind: Kind, val active: Boolean, val at: Long)

    /**
     * v1.7.0 (bug «Я.Музыка на паузе — кнопки радио не работают»): the most recently STARTED source that is still
     * active wins (a playing session, the phone playing over BT, the radio started from the launcher / reported on);
     * a PAUSED session is chosen only when nothing is active. Ties → earlier in the list (sessions first).
     */
    fun pick(c: List<Cand>): Int {
        val act = c.indices.filter { c[it].active }
        if (act.isNotEmpty()) return act.maxWithOrNull(compareBy<Int> { c[it].at }.thenByDescending { it })!!
        if (c.isEmpty()) return -1
        return c.indices.maxWithOrNull(compareBy<Int> { c[it].at }.thenByDescending { it })!!
    }

    private val h = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var msm: MediaSessionManager? = null
    private var comp: ComponentName? = null
    private val listeners = LinkedHashSet<() -> Unit>()
    private var sessions: List<MediaController> = emptyList()
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private val lastPlay = HashMap<String, Long>()
    /** Chosen by hand in «Источник» (kept until another app starts playing). */
    var pinned: String? = null
    var current: MediaController? = null
        private set
    /** Kind of the active source ([current] is set only for SESSION). */
    var kind: Kind = Kind.NONE
        private set
    /** True when notification access is granted and sessions can be read. */
    var access = false
        private set

    fun listenerEnabled(c: Context): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == c.packageName } || flat.contains(c.packageName)
    }

    /** Adds a listener; starts following sessions with the first one. Returns [access]. */
    fun attach(c: Context, l: () -> Unit): Boolean {
        listeners.add(l)
        if (app == null) start(c.applicationContext)
        else if (!access) start(c.applicationContext)
        else refresh()
        return access
    }

    fun detach(l: () -> Unit) {
        listeners.remove(l)
        if (listeners.isEmpty()) stop()
    }

    private val sessListener = MediaSessionManager.OnActiveSessionsChangedListener { setSessions(it ?: emptyList()) }

    private fun start(c: Context) {
        app = c
        BtMeta.register(c) { if (BtMeta.playing && BtMeta.fresh()) Radio.on = false; repick() }
        Radio.register(c) { repick() }
        // the radio was the last source before a restart (FYT resumes the tuner on its own) → keep it, as the oldest start
        if (Radio.at == 0L && isRadio(c, safeLast())) { Radio.pkg = safeLast(); Radio.on = true; Radio.at = 1L }
        if (msm == null) msm = c.getSystemService(MediaSessionManager::class.java)
        if (comp == null) comp = ComponentName(c, MediaListener::class.java)
        access = false
        if (!listenerEnabled(c)) { setSessions(emptyList()); return }
        try { NotificationListenerService.requestRebind(comp) } catch (_: Throwable) {}
        try {
            msm!!.addOnActiveSessionsChangedListener(sessListener, comp, h)
            access = true
            setSessions(msm!!.getActiveSessions(comp))
        } catch (e: SecurityException) { access = false; setSessions(emptyList()) }
        catch (e: Throwable) { access = false; setSessions(emptyList()) }
    }

    private fun stop() {
        app?.let { BtMeta.unregister(it); Radio.unregister(it) }
        try { msm?.removeOnActiveSessionsChangedListener(sessListener) } catch (_: Throwable) {}
        callbacks.forEach { (c, cb) -> try { c.unregisterCallback(cb) } catch (_: Throwable) {} }
        callbacks.clear(); sessions = emptyList(); current = null; app = null; access = false
    }

    /** Re-reads the session list (e.g. coming back to the home screen). */
    fun refresh() {
        val m = msm ?: return; val cp = comp ?: return
        try { setSessions(m.getActiveSessions(cp)) } catch (_: Throwable) {}
    }

    private fun setSessions(list: List<MediaController>) {
        val mine = app?.packageName
        val l = list.filter { it.packageName != mine }
        // drop callbacks of gone sessions, add callbacks on new ones
        val keep = l.map { it.sessionToken }.toSet()
        callbacks.keys.filter { it.sessionToken !in keep }.forEach { c -> try { c.unregisterCallback(callbacks.remove(c)!!) } catch (_: Throwable) { callbacks.remove(c) } }
        val have = callbacks.keys.map { it.sessionToken }.toSet()
        for (c in l) if (c.sessionToken !in have) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (isPlaying(state?.state)) { mark(c.packageName); if (pinned != null && pinned != c.packageName) pinned = null }
                    repick()
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) { if (c.sessionToken == current?.sessionToken) emit() else repick() }
                override fun onSessionDestroyed() { h.post { refresh() } }
            }
            try { c.registerCallback(cb, h); callbacks[c] = cb } catch (_: Throwable) {}
        }
        sessions = l
        l.filter { isPlaying(it.playbackState?.state) && (lastPlay[it.packageName] ?: 0L) == 0L }.forEach { mark(it.packageName) }
        repick()
    }

    private fun mark(pkg: String) {
        lastPlay[pkg] = SystemClock.elapsedRealtime()
        // an Android player started after the radio → FYT switches the audio source away from the tuner
        if (Radio.on && !isRadio(app, pkg)) Radio.on = false
        if (Prefs.lastPlayer != pkg) try { Prefs.lastPlayer = pkg } catch (_: Throwable) {}
    }

    private fun info(c: MediaController): Info {
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val lp = lastPlay[c.packageName] ?: if (c.packageName == safeLast()) 1L else 0L
        return Info(c.packageName, c.playbackState?.state ?: PlaybackState.STATE_NONE, !title.isNullOrBlank(), lp)
    }
    private fun safeLast(): String = try { Prefs.lastPlayer } catch (_: Throwable) { "" }

    private fun repick() {
        val l = sessions
        val infos = l.map { info(it) }
        val i = choose(infos, pinned)
        val s = if (i >= 0) l[i] else null
        val cands = ArrayList<Cand>(); val kinds = ArrayList<Kind>()
        if (s != null) {
            val inf = infos[i]
            // a session picked by hand counts as just started
            val at = if (pinned == s.packageName) maxOf(inf.lastPlay, pinnedAt) else inf.lastPlay
            cands.add(Cand(Kind.SESSION, isPlaying(inf.state), at)); kinds.add(Kind.SESSION)
        }
        if (BtMeta.fresh() && (BtMeta.playing || BtMeta.at > 0)) { cands.add(Cand(Kind.PHONE, BtMeta.playing, BtMeta.playAt)); kinds.add(Kind.PHONE) }
        if (Radio.on || Radio.at > 0) { cands.add(Cand(Kind.RADIO, Radio.on, Radio.at)); kinds.add(Kind.RADIO) }
        val k = pick(cands)
        kind = if (k >= 0) kinds[k] else Kind.NONE
        current = if (kind == Kind.SESSION) s else null
        emit()
    }
    private var pinnedAt = 0L

    // ---- radio of the head unit (FYT / TEYES «Радио» usually publishes NO media session) ----
    val RADIO_PKGS = listOf("com.syu.radio", "com.syu.ms.radio", "com.microntek.radio", "com.android.fmradio", "com.navimods.radio", "com.spd.radio")
    fun isRadio(c: Context?, pkg: String?): Boolean = pkg != null && (pkg in RADIO_PKGS ||
        (c != null && try { Tiles.resolve(c, 1).pkg == pkg } catch (_: Throwable) { false }))
    private fun hasSession(pkg: String) = sessions.any { it.packageName == pkg }

    /**
     * Called when the launcher opens an app. Radio without a media session → it becomes the active source (until another
     * player / the phone starts); radio WITH a session → that session is pinned. Other apps: nothing.
     */
    fun launched(c: Context, pkg: String) {
        if (!isRadio(c, pkg)) return
        if (hasSession(pkg)) { pin(pkg); return }
        Radio.started(pkg); pinned = null
        try { Prefs.lastPlayer = pkg } catch (_: Throwable) {}
        repick()
    }

    private fun emit() { listeners.toList().forEach { try { it() } catch (_: Throwable) {} } }

    val all: List<MediaController> get() = sessions
    val playing: Boolean get() = isPlaying(current?.playbackState?.state)

    /** Pins a session chosen by hand («Источник»). */
    fun pin(pkg: String?) { pinned = pkg; pinnedAt = SystemClock.elapsedRealtime(); if (pkg != null) Radio.on = false; repick() }
    /** «Радио» chosen by hand in «Источник». */
    fun pinRadio(pkg: String) { Radio.started(pkg); pinned = null; repick() }

    /** Phone audio over Bluetooth is what plays (no unit session plays, but the BT stack / BT app reported a playing track). */
    fun phoneActive(): Boolean = kind == Kind.PHONE || (kind == Kind.NONE && BtMeta.fresh() && BtMeta.playing)
    fun radioActive(): Boolean = kind == Kind.RADIO

    /** Session that is the phone over Bluetooth (AOSP AVRCP-controller session or the head unit's BT app). */
    fun isPhone(c: Context, pkg: String?): Boolean = pkg != null && (pkg == "com.android.bluetooth" || pkg.startsWith("com.syu.bt") ||
        pkg.contains("btmusic", true) || pkg.contains("bluetooth", true) || pkg == CamApps.bt(c))

    /** Prev / play-pause / next for what is shown; without a session → media keys (phone BT, players without a session). */
    fun command(c: Context, code: Int) {
        if (radioActive()) { Radio.command(c, code); return }
        val mc = current
        if (mc != null && !phoneActive()) {
            val t = mc.transportControls
            try {
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> t.skipToPrevious()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> t.skipToNext()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> t.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> t.pause()
                    else -> if (isPlaying(mc.playbackState?.state)) t.pause() else t.play()
                }
                return
            } catch (_: Throwable) {}
        }
        key(c, code)
    }

    fun key(c: Context, code: Int) {
        val am = c.getSystemService(AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        try {
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        } catch (_: Throwable) {}
    }

    /** Resume music if nothing plays (used when the navigator is opened). */
    fun resume(c: Context) {
        if (playing || radioActive() || (phoneActive() && BtMeta.playing)) return
        val mc = current
        if (mc != null) { try { mc.transportControls.play(); return } catch (_: Throwable) {} }
        key(c, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    /**
     * After a player was opened from the card: when its session shows up (≤ 6 s) and does not play, press Play once.
     * Яндекс Музыка / Apple Music / Spotify restore their last queue on Play.
     */
    fun playWhenReady(c: Context, pkg: String) {
        val ctx = c.applicationContext
        val until = SystemClock.elapsedRealtime() + 6000
        val r = object : Runnable {
            override fun run() {
                if (current?.packageName != pkg || !playing) {
                    val s = sessions.firstOrNull { it.packageName == pkg }
                    if (s != null) {
                        if (!isPlaying(s.playbackState?.state)) try { s.transportControls.play() } catch (_: Throwable) {}
                        pinned = pkg; repick(); return
                    }
                    refresh()
                    if (SystemClock.elapsedRealtime() < until) h.postDelayed(this, 700)
                }
            }
        }
        h.postDelayed(r, 1200)
        @Suppress("UNUSED_VARIABLE") val unused = ctx
    }

    fun title(mc: MediaController?): String? = mc?.metadata?.let { it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) }
}

/**
 * v1.7.0 phone over Bluetooth without a media session. Android 10 has no public API to read AVRCP metadata of a
 * connected phone; what exists is collected here (all receive-only, nothing is sent to the BT module / MCU):
 *  • AOSP AVRCP controller broadcast (A2DP-sink builds): TRACK_EVENT with MediaMetadata + PlaybackState;
 *  • the de-facto "music meta" broadcasts several players and head-unit BT apps send (track / artist / playing).
 * If the TEYES BT app publishes a normal MediaSession, [MediaHub] uses that instead (preferred, also controls).
 */
object BtMeta {
    const val AVRCP_TRACK = "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT"
    const val AVRCP_META = "android.bluetooth.avrcp-controller.profile.extra.METADATA"
    const val AVRCP_PLAY = "android.bluetooth.avrcp-controller.profile.extra.PLAYBACK"
    val ACTIONS = listOf(AVRCP_TRACK, "com.android.music.metachanged", "com.android.music.playstatechanged",
        "com.syu.bt.music.info", "com.syu.bt.ACTION_MUSIC_INFO", "com.android.bluetooth.a2dpsink.metachanged")
    var title: String? = null; var artist: String? = null; var album: String? = null
    var playing = false; var at = 0L; var phone = false
    /** When the phone last switched to playing (for «most recently started wins»). */
    var playAt = 0L
    private fun updPlaying(p: Boolean) { if (p && !playing) playAt = SystemClock.elapsedRealtime(); playing = p }
    private var rcv: android.content.BroadcastReceiver? = null
    private var cb: (() -> Unit)? = null

    fun fresh() = at > 0 && SystemClock.elapsedRealtime() - at < 30 * 60_000L

    fun register(c: Context, onChange: () -> Unit) {
        cb = onChange
        if (rcv != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: android.content.Intent) { if (parse(i)) cb?.invoke() }
        }
        try { c.registerReceiver(r, android.content.IntentFilter().apply { ACTIONS.forEach { addAction(it) } }); rcv = r } catch (_: Throwable) {}
    }
    fun unregister(c: Context) { rcv?.let { try { c.unregisterReceiver(it) } catch (_: Throwable) {} }; rcv = null; cb = null }

    /** Reads one broadcast; true when something changed. Public for tests. */
    fun parse(i: android.content.Intent): Boolean {
        val ex = i.extras ?: return false
        if (i.action == AVRCP_TRACK) {
            @Suppress("DEPRECATION") val md = ex.get(AVRCP_META) as? MediaMetadata
            @Suppress("DEPRECATION") val ps = ex.get(AVRCP_PLAY) as? PlaybackState
            if (md != null) { title = md.getString(MediaMetadata.METADATA_KEY_TITLE); artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST); album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) }
            if (ps != null) updPlaying(MediaHub.isPlaying(ps.state))
            phone = true; at = SystemClock.elapsedRealtime(); return md != null || ps != null
        }
        fun str(vararg k: String) = k.firstNotNullOfOrNull { (ex.get(it) as? CharSequence)?.toString()?.takeIf { s -> s.isNotBlank() } }
        val t = str("track", "title", "TITLE", "name"); val ar = str("artist", "ARTIST"); val al = str("album", "ALBUM")
        val pl = (ex.get("playing") ?: ex.get("isPlaying") ?: ex.get("isplaying")) as? Boolean
        if (t == null && pl == null) return false
        if (t != null) { title = t; artist = ar; album = al }
        if (pl != null) updPlaying(pl)
        phone = i.action?.contains("bt", true) == true || i.action?.contains("bluetooth", true) == true
        at = SystemClock.elapsedRealtime(); return true
    }
}

/**
 * v1.7.0 head-unit radio without a media session (FYT/TEYES com.syu.radio). Control without root / MCU access:
 *  • prev / next station: the OEM service the stock radio widget and steering-wheel path use —
 *    startService(com.syu.broadcast/.MyService, action com.syu.radio.prevservice / nextservice)
 *    (documented by OpenRadioFM's FYT engine; Tasker users use the same). Only that action is sent — no settings.
 *  • play / pause: there is no public command → the radio app is opened (pause it there).
 *  • fallback when the service is missing: media keys (go to the last media-button receiver — may not be the radio).
 * State: «on» when started from the launcher, or by receive-only reports (com.microntek.radio.report power/freq on
 * MTC units; FYT action names are not documented — guessed names listed in [ACTIONS], harmless if they never come).
 */
object Radio {
    val ACTIONS = listOf("com.microntek.radio.report", "com.syu.radio.report", "com.syu.radio.info", "com.syu.radio.state")
    var on = false
    var at = 0L
    var pkg: String? = null
    /** Frequency text, e.g. "FM 101.4", when a report carried it. */
    var freq: String? = null
    private var rcv: android.content.BroadcastReceiver? = null
    private var cb: (() -> Unit)? = null

    fun started(p: String?) { pkg = p ?: pkg; on = true; at = SystemClock.elapsedRealtime() }

    fun register(c: Context, onChange: () -> Unit) {
        cb = onChange
        if (rcv != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: android.content.Intent) { if (parse(i)) cb?.invoke() }
        }
        try { c.registerReceiver(r, android.content.IntentFilter().apply { ACTIONS.forEach { addAction(it) } }); rcv = r } catch (_: Throwable) {}
    }
    fun unregister(c: Context) { rcv?.let { try { c.unregisterReceiver(it) } catch (_: Throwable) {} }; rcv = null; cb = null }

    /** Public for tests: type=power state=true/false; type=content freq=Hz (or kHz / MHz). */
    fun parse(i: android.content.Intent): Boolean {
        val ex = i.extras ?: return false
        @Suppress("DEPRECATION") fun any(k: String) = ex.get(k)
        val type = any("type")?.toString()
        val st = any("state"); val fq = any("freq") ?: any("frequency")
        var ch = false
        if (type == "power" || st is Boolean) {
            val v = (st as? Boolean) ?: st?.toString()?.toBooleanStrictOrNull()
            if (v != null) { if (v && !on) at = SystemClock.elapsedRealtime(); on = v; ch = true }
        }
        if (fq != null) {
            val f = fq.toString().toDoubleOrNull()
            if (f != null && f > 0) {
                val mhz = when { f > 1e7 -> f / 1e6; f > 1e4 -> f / 1e3; f > 2000 -> f / 100; else -> f }   // Hz / kHz / 10 kHz / MHz
                freq = if (mhz in 60.0..120.0) "FM " + String.format(java.util.Locale.US, "%.1f", mhz) else "AM " + Math.round(if (mhz < 2) mhz * 1000 else mhz)
                if (!on) { on = true; at = SystemClock.elapsedRealtime() }
                ch = true
            }
        }
        return ch
    }

    fun command(c: Context, code: Int) {
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (!seek(c, code == KeyEvent.KEYCODE_MEDIA_NEXT)) MediaHub.key(c, code)
            }
            else -> open(c)
        }
    }

    /** Next / previous station through the FYT OEM service. False when it is not there. */
    fun seek(c: Context, next: Boolean): Boolean = try {
        val i = android.content.Intent(if (next) "com.syu.radio.nextservice" else "com.syu.radio.prevservice")
            .setClassName("com.syu.broadcast", "com.syu.broadcast.MyService")
        c.startService(i) != null
    } catch (_: Throwable) { false }

    fun open(c: Context) {
        val p = pkg ?: MediaHub.RADIO_PKGS.firstOrNull { c.packageManager.getLaunchIntentForPackage(it) != null } ?: return
        val i = c.packageManager.getLaunchIntentForPackage(p) ?: return
        try { c.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
    }
}
