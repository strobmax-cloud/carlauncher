package com.goga.carlauncher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.goga.carlauncher.Ui.u

/**
 * Floating mini player shown over the navigator (SYSTEM_ALERT_WINDOW + foreground service).
 * Cover, title / artist, ⏮ ⏯ ⏭ through the same media session the home screen uses. Draggable,
 * tap the cover to collapse into a round bubble, position is remembered. Hidden while the launcher itself is in front.
 * Cost: one small window, no animations, art scaled to ~150 px.
 */
class MiniPlayer : Service() {
    companion object {
        const val ACTION_STOP = "com.goga.carlauncher.MINI_STOP"
        private const val CH = "mini"
        @Volatile var instance: MiniPlayer? = null; private set
        private var launcherInFront = false

        fun allowed(c: Context) = Settings.canDrawOverlays(c) &&
            Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")?.contains(c.packageName) == true

        /** Returns true if the mini player is (or will be) shown. */
        fun start(c: Context, auto: Boolean): Boolean {
            if (!allowed(c)) return false
            val i = Intent(c, MiniPlayer::class.java)
            return try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i); true } catch (e: Exception) { false }
                .also { if (auto) launcherInFront = false }
        }
        fun stop(c: Context) { try { c.stopService(Intent(c, MiniPlayer::class.java)) } catch (_: Exception) {} }
        /** ClassicActivity resumed/paused: the mini player hides while the launcher (with its own player) is visible. */
        fun setLauncherInFront(v: Boolean) { launcherInFront = v; instance?.applyVisibility() }
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var root: FrameLayout? = null
    private lateinit var full: LinearLayout
    private lateinit var bubble: FrameLayout
    private lateinit var art: ImageView
    private lateinit var bubbleArt: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var playIv: ImageView
    private var controller: MediaController? = null
    private var artKey: Any? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        startForeground(7, notification())
        val dm = resources.displayMetrics
        if (Ui.s == 1f) Ui.s = minOf(maxOf(dm.widthPixels, dm.heightPixels) / 1280f, minOf(dm.widthPixels, dm.heightPixels) / 720f)
        wm = getSystemService(WindowManager::class.java)
        try { build(); instance = this; startMedia() } catch (e: Exception) { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        applyVisibility()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Мини-плеер", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val stop = PendingIntent.getService(this, 1, Intent(this, MiniPlayer::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_music).setContentTitle("Мини-плеер поверх навигатора").setContentText("Нажмите, чтобы закрыть")
            .setContentIntent(stop).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Закрыть", stop).build()).build()
    }

    // ---------------- window ----------------
    private val wFull get() = u(470)
    private val hFull get() = u(96)
    private val dBubble get() = u(84)

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        val accent = Themes.active().accent
        val r = FrameLayout(this)
        full = Ui.hbox(this).apply { setPadding(u(12), u(12), u(10), u(12)); background = Ui.round(0xF0161618.toInt(), u(26)) }
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Ui.round(0x22FFFFFF, u(14)) }
        Ui.clipRound(art, u(14))
        full.addView(art, Ui.lin(u(72), u(72)))
        val col = Ui.vbox(this).apply { setPadding(u(14), 0, u(8), 0) }
        title = Ui.text(this, 19, Color.WHITE, Ui.BOLD); artist = Ui.text(this, 15, Ui.alpha(Color.WHITE, 0.65f))
        col.addView(title, Ui.lin(Ui.MATCH, Ui.WRAP)); col.addView(artist, Ui.lin(Ui.MATCH, Ui.WRAP).apply { topMargin = u(4) })
        full.addView(col, Ui.lin(0, Ui.WRAP, 1f))
        full.addView(Ui.iconButton(this, R.drawable.ic_prev, 52, 26, 0x24FFFFFF, Color.WHITE, 0x55FFFFFF) { MediaHub.command(this, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) })
        val pp = Ui.iconButton(this, R.drawable.ic_play, 60, 30, accent, Color.WHITE, Ui.blend(accent, 0x40000000)) { MediaHub.command(this, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        playIv = pp.getChildAt(0) as ImageView
        full.addView(pp, (pp.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = u(8); rightMargin = u(8) })
        full.addView(Ui.iconButton(this, R.drawable.ic_next, 52, 26, 0x24FFFFFF, Color.WHITE, 0x55FFFFFF) { MediaHub.command(this, android.view.KeyEvent.KEYCODE_MEDIA_NEXT) })
        val close = Ui.text(this, 22, Ui.alpha(Color.WHITE, 0.6f), Ui.MEDIUM).apply { text = "✕"; gravity = Gravity.CENTER; isClickable = true
            background = Ui.pressableOval(0, 0x33FFFFFF); setOnClickListener { stopSelf() } }
        full.addView(close, Ui.lin(u(44), u(44)).apply { leftMargin = u(6) })
        r.addView(full, FrameLayout.LayoutParams(wFull, hFull))

        bubble = FrameLayout(this).apply { background = Ui.oval(accent); setPadding(u(4), u(4), u(4), u(4)) }
        bubbleArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Ui.oval(0xFF1C1C1E.toInt())
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; clipToOutline = true }
        bubble.addView(bubbleArt, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        r.addView(bubble, FrameLayout.LayoutParams(dBubble, dBubble))

        // drag anywhere that is not a button; a tap on the cover collapses, on the text opens the player, on the bubble expands
        drag(art) { setCollapsed(true) }
        drag(col) { controller?.packageName?.let { p -> packageManager.getLaunchIntentForPackage(p)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
        drag(full) {}
        drag(bubble) { setCollapsed(false) }

        lp = WindowManager.LayoutParams(Ui.WRAP, Ui.WRAP,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
        }
        root = r
        setCollapsed(Prefs.miniCollapsed, save = false)
        wm.addView(r, lp)
        showTrack(null)
    }

    private fun screen(): Pair<Int, Int> { val dm = resources.displayMetrics; return dm.widthPixels to dm.heightPixels }

    private fun place() {
        val (sw, sh) = screen()
        val w = if (Prefs.miniCollapsed) dBubble else wFull; val hh = if (Prefs.miniCollapsed) dBubble else hFull
        val x = Prefs.miniX.takeIf { it >= 0 } ?: ((sw - wFull) / 2)
        val y = Prefs.miniY.takeIf { it >= 0 } ?: (sh - hFull - u(110))
        lp.x = x.coerceIn(0, maxOf(0, sw - w)); lp.y = y.coerceIn(0, maxOf(0, sh - hh))
    }

    private fun setCollapsed(c: Boolean, save: Boolean = true) {
        if (save) Prefs.miniCollapsed = c
        full.visibility = if (c) View.GONE else View.VISIBLE
        bubble.visibility = if (c) View.VISIBLE else View.GONE
        place()
        root?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, lp) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun drag(v: View, onTap: () -> Unit) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - dx; val my = e.rawY - dy
                    if (!moved && (Math.abs(mx) > slop || Math.abs(my) > slop)) moved = true
                    if (moved) {
                        val (sw, sh) = screen(); val rv = root ?: return@setOnTouchListener true
                        lp.x = (sx + mx.toInt()).coerceIn(0, maxOf(0, sw - rv.width)); lp.y = (sy + my.toInt()).coerceIn(0, maxOf(0, sh - rv.height))
                        wm.updateViewLayout(rv, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (moved) { Prefs.miniX = lp.x; Prefs.miniY = lp.y } else onTap(); true }
                else -> true
            }
        }
    }

    fun applyVisibility() {
        val r = root ?: return
        r.visibility = if (launcherInFront || controller == null) View.GONE else View.VISIBLE
    }

    // ---------------- media ----------------

    private fun startMedia() {
        if (!MediaHub.attach(this, hub)) { stopSelf(); return }   // v1.7.0: the same choice as the home card (what plays now)
        hub()
    }

    private val hub: () -> Unit = { controller = MediaHub.current; showTrack(controller); applyVisibility() }
    @Suppress("unused") private fun pick(list: List<MediaController>?) = hub()

    private fun tc(f: (MediaController.TransportControls) -> Unit) { controller?.transportControls?.let(f) }

    private fun showTrack(c: MediaController?) {
        if (root == null) return
        val md = c?.metadata
        title.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: (if (c == null) "Ничего не играет" else "Без названия")
        artist.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: (c?.packageName?.let { Tiles.label(this, it) } ?: "")
        playIv.setImageResource(if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = if (bmp == null) (md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)) else null
        val key: Any? = bmp ?: uri
        if (key != artKey) {
            artKey = key
            if (key == null) setArt(null) else {
                val target = u(150); val cr = contentResolver
                AppRepo.exec.execute {
                    val out = try { if (bmp != null) ArtLoader.scale(bmp, target) else ArtLoader.fromUri(cr, uri!!, target) } catch (e: Throwable) { null }
                    h.post { if (artKey === key) setArt(out) }
                }
            }
        }
        applyVisibility()
    }

    private fun setArt(b: Bitmap?) {
        if (b != null) { art.setPadding(0, 0, 0, 0); art.setImageBitmap(b); bubbleArt.setPadding(0, 0, 0, 0); bubbleArt.setImageBitmap(b) }
        else { val p = u(16); art.setPadding(p, p, p, p); art.setImageResource(R.drawable.tile_music); bubbleArt.setPadding(p, p, p, p); bubbleArt.setImageResource(R.drawable.tile_music) }
    }

    override fun onDestroy() {
        instance = null
        MediaHub.detach(hub); controller = null
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        super.onDestroy()
    }
}
